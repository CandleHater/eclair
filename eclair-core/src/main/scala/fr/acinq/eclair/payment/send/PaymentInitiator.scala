/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment.send

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.{Channel, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.payment.{LocalFailure, OutgoingPacket, PaymentFailed, PaymentRequest}
import fr.acinq.eclair.router.{ChannelHop, Hop, NodeHop, RouteParams}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{Onion, OnionTlv}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, randomBytes32}

/**
 * Created by PM on 29/08/2016.
 */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  import PaymentInitiator._

  override def receive: Receive = {
    case r: SendPaymentRequest =>
      val paymentId = UUID.randomUUID()
      sender ! paymentId
      val paymentCfg = SendPaymentConfig(paymentId, paymentId, r.externalId, r.paymentHash, r.finalAmount, r.recipientNodeId, Upstream.Local(paymentId), r.paymentRequest, storeInDb = true, publishEvent = true, Nil)
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      r.paymentRequest match {
        case Some(invoice) if !invoice.features.supported =>
          sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException(s"can't send payment: unknown invoice features (${invoice.features})")) :: Nil)
        case Some(invoice) if invoice.features.allowMultiPart => invoice.paymentSecret match {
          case Some(paymentSecret) => spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(paymentSecret, r.recipientNodeId, r.finalAmount, finalExpiry, r.maxAttempts, r.assistedRoutes, r.routeParams)
          case None => sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException("can't send payment: multi-part invoice is missing a payment secret")) :: Nil)
        }
        case _ =>
          // NB: we only generate legacy payment onions for now for maximum compatibility.
          spawnPaymentFsm(paymentCfg) forward SendPayment(r.recipientNodeId, FinalLegacyPayload(r.finalAmount, finalExpiry), r.maxAttempts, r.assistedRoutes, r.routeParams)
      }

    case r: SendTrampolinePaymentRequest =>
      val paymentId = UUID.randomUUID()
      sender ! paymentId
      if (!r.paymentRequest.features.allowTrampoline && r.paymentRequest.amount.isEmpty) {
        sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException("cannot pay a 0-value invoice via trampoline-to-legacy (trampoline may steal funds)")) :: Nil)
      } else {
        val finalPayload = if (r.paymentRequest.features.allowMultiPart) {
          Onion.createMultiPartPayload(r.finalAmount, r.finalAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret.get)
        } else {
          Onion.createSinglePartPayload(r.finalAmount, r.finalExpiry(nodeParams.currentBlockHeight), r.paymentRequest.paymentSecret)
        }
        val trampolineRoute = Seq(
          NodeHop(nodeParams.nodeId, r.trampolineNodeId, nodeParams.expiryDeltaBlocks, 0 msat),
          NodeHop(r.trampolineNodeId, r.recipientNodeId, r.trampolineExpiryDelta, r.trampolineFees) // for now we only use a single trampoline hop
        )
        // We assume that the trampoline node supports multi-part payments (it should).
        val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (r.paymentRequest.features.allowTrampoline) {
          OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(r.paymentHash, trampolineRoute, finalPayload)
        } else {
          OutgoingPacket.buildTrampolineToLegacyPacket(r.paymentRequest, trampolineRoute, finalPayload)
        }
        // We generate a random secret for this payment to avoid leaking the invoice secret to the first trampoline node.
        val trampolineSecret = randomBytes32
        val paymentCfg = SendPaymentConfig(paymentId, paymentId, None, r.paymentHash, r.finalAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = true, trampolineRoute.tail)
        spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(trampolineSecret, r.trampolineNodeId, trampolineAmount, trampolineExpiry, 1, r.paymentRequest.routingInfo, r.routeParams, Seq(OnionTlv.TrampolineOnion(trampolineOnion.packet)))
      }

    case r: SendPaymentToRouteRequest =>
      val paymentId = UUID.randomUUID()
      val parentPaymentId = r.parentId.getOrElse(UUID.randomUUID())
      val finalExpiry = r.finalExpiry(nodeParams.currentBlockHeight)
      val additionalHops = r.trampolineNodes.sliding(2).map(hop => NodeHop(hop.head, hop(1), CltvExpiryDelta(0), 0 msat)).toSeq
      val paymentCfg = SendPaymentConfig(paymentId, parentPaymentId, r.externalId, r.paymentHash, r.finalAmount, r.recipientNodeId, Upstream.Local(paymentId), Some(r.paymentRequest), storeInDb = true, publishEvent = true, additionalHops)
      val payFsm = spawnPaymentFsm(paymentCfg)
      if (r.trampolineNodes.isEmpty) {
        sender ! SendPaymentToRouteResponse(paymentId, parentPaymentId, None)
        r.paymentRequest.paymentSecret match {
          case Some(paymentSecret) => payFsm forward SendPaymentToRoute(r.route, Onion.createMultiPartPayload(r.amount, r.finalAmount, finalExpiry, paymentSecret))
          case None => payFsm forward SendPaymentToRoute(r.route, FinalLegacyPayload(r.finalAmount, finalExpiry))
        }
      } else {
        // We generate a random secret for the payment to the first trampoline node.
        val trampolineSecret = r.trampolineSecret.getOrElse(randomBytes32)
        sender ! SendPaymentToRouteResponse(paymentId, parentPaymentId, Some(trampolineSecret))
        val finalPayload = r.paymentRequest.paymentSecret match {
          case Some(paymentSecret) => Onion.createMultiPartPayload(r.finalAmount, r.finalAmount, finalExpiry, paymentSecret)
          case None => Onion.createSinglePartPayload(r.finalAmount, finalExpiry)
        }
        val trampolineRoute = NodeHop(nodeParams.nodeId, r.trampolineNodes.head, nodeParams.expiryDeltaBlocks, 0 msat) +:
          NodeHop(r.trampolineNodes.head, r.trampolineNodes(1), r.trampolineExpiryDelta, r.trampolineFees) +:
          additionalHops.tail
        val (trampolineAmount, trampolineExpiry, trampolineOnion) = if (r.paymentRequest.features.allowTrampoline) {
          OutgoingPacket.buildPacket(Sphinx.TrampolinePacket)(r.paymentHash, trampolineRoute, finalPayload)
        } else {
          OutgoingPacket.buildTrampolineToLegacyPacket(r.paymentRequest, trampolineRoute, finalPayload)
        }
        payFsm forward SendPaymentToRoute(r.route, Onion.createMultiPartPayload(r.amount, trampolineAmount, trampolineExpiry, trampolineSecret, Seq(OnionTlv.TrampolineOnion(trampolineOnion.packet))))
      }
  }

  def spawnPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(PaymentLifecycle.props(nodeParams, paymentCfg, router, register))

  def spawnMultiPartPaymentFsm(paymentCfg: SendPaymentConfig): ActorRef = context.actorOf(MultiPartPaymentLifecycle.props(nodeParams, paymentCfg, relayer, router, register))

}

object PaymentInitiator {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, relayer, register)

  /**
   * We temporarily let the caller decide to use Trampoline (instead of a normal payment) and set the fees/cltv.
   * It's the caller's responsibility to retry with a higher fee/cltv on certain failures.
   * Once we have trampoline fee estimation built into the router, the decision to use Trampoline or not should be done
   * automatically by the router instead of the caller.
   *
   * @param finalAmount           amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param trampolineFees        fees for the trampoline node.
   * @param paymentRequest        Bolt 11 invoice.
   * @param trampolineNodeId      id of the trampoline node.
   * @param finalExpiryDelta      expiry delta for the final recipient.
   * @param trampolineExpiryDelta expiry delta for the trampoline node.
   * @param routeParams           (optional) parameters to fine-tune the routing algorithm.
   */
  case class SendTrampolinePaymentRequest(finalAmount: MilliSatoshi,
                                          trampolineFees: MilliSatoshi,
                                          paymentRequest: PaymentRequest,
                                          trampolineNodeId: PublicKey,
                                          finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                          trampolineExpiryDelta: CltvExpiryDelta,
                                          routeParams: Option[RouteParams] = None) {
    val recipientNodeId = paymentRequest.nodeId
    val paymentHash = paymentRequest.paymentHash

    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  /**
   * @param finalAmount      amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param paymentHash      payment hash.
   * @param recipientNodeId  id of the final recipient.
   * @param maxAttempts      maximum number of retries.
   * @param finalExpiryDelta expiry delta for the final recipient.
   * @param paymentRequest   (optional) Bolt 11 invoice.
   * @param externalId       (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param assistedRoutes   (optional) routing hints (usually from a Bolt 11 invoice).
   * @param routeParams      (optional) parameters to fine-tune the routing algorithm.
   */
  case class SendPaymentRequest(finalAmount: MilliSatoshi,
                                paymentHash: ByteVector32,
                                recipientNodeId: PublicKey,
                                maxAttempts: Int,
                                finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                paymentRequest: Option[PaymentRequest] = None,
                                externalId: Option[String] = None,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  /**
   * The sender can skip the routing algorithm by specifying the route to use.
   * When combining with MPP and Trampoline, extra-care must be taken to make sure payments are correctly grouped: only
   * amount, route and trampolineNodes should be changing.
   *
   * Example 1: MPP containing two HTLCs for a 600 msat invoice:
   * SendPaymentToRouteRequest(200 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, bob, dave), None, 0 msat, CltvExpiryDelta(0), Nil)
   * SendPaymentToRouteRequest(400 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, carol, dave), None, 0 msat, CltvExpiryDelta(0), Nil)
   *
   * Example 2: Trampoline with MPP for a 600 msat invoice and 100 msat trampoline fees:
   * SendPaymentToRouteRequest(250 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, bob, dave), secret, 100 msat, CltvExpiryDelta(144), Seq(dave, peter))
   * SendPaymentToRouteRequest(450 msat, 600 msat, None, parentId, invoice, CltvExpiryDelta(9), Seq(alice, carol, dave), secret, 100 msat, CltvExpiryDelta(144), Seq(dave, peter))
   *
   * @param amount                amount that should be received by the last node in the route (should take trampolineFees
   *                              into account).
   * @param finalAmount           amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   *                              This amount may be split between multiple requests if using MPP.
   * @param externalId            (optional) externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param parentId              id of the whole payment. When manually sending a multi-part payment, you need to make
   *                              sure all partial payments use the same parentId. If not provided, a random parentId will
   *                              be generated that can be used for the remaining partial payments.
   * @param paymentRequest        Bolt 11 invoice.
   * @param finalExpiryDelta      expiry delta for the final recipient.
   * @param route                 route to use to reach either the final recipient or the first trampoline node.
   * @param trampolineSecret      if trampoline is used, this is a secret to protect the payment to the first trampoline
   *                              node against probing. When manually sending a multi-part payment, you need to make sure
   *                              all partial payments use the same trampolineSecret.
   * @param trampolineFees        if trampoline is used, fees for the first trampoline node. This value must be the same
   *                              for all partial payments in the set.
   * @param trampolineExpiryDelta if trampoline is used, expiry delta for the first trampoline node. This value must be
   *                              the same for all partial payments in the set.
   * @param trampolineNodes       if trampoline is used, list of trampoline nodes to use.
   */
  case class SendPaymentToRouteRequest(amount: MilliSatoshi,
                                       finalAmount: MilliSatoshi,
                                       externalId: Option[String],
                                       parentId: Option[UUID],
                                       paymentRequest: PaymentRequest,
                                       finalExpiryDelta: CltvExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA,
                                       route: Seq[PublicKey],
                                       trampolineSecret: Option[ByteVector32],
                                       trampolineFees: MilliSatoshi,
                                       trampolineExpiryDelta: CltvExpiryDelta,
                                       trampolineNodes: Seq[PublicKey]) {
    val recipientNodeId = paymentRequest.nodeId
    val paymentHash = paymentRequest.paymentHash

    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

  /**
   * @param paymentId        id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId         id of the whole payment. When manually sending a multi-part payment, you need to make sure
   *                         all partial payments use the same parentId.
   * @param trampolineSecret if trampoline is used, this is a secret to protect the payment to the first trampoline node
   *                         against probing. When manually sending a multi-part payment, you need to make sure all
   *                         partial payments use the same trampolineSecret.
   */
  case class SendPaymentToRouteResponse(paymentId: UUID, parentId: UUID, trampolineSecret: Option[ByteVector32])

  /**
   * Configuration for an instance of a payment state machine.
   *
   * @param id              id of the outgoing payment (mapped to a single outgoing HTLC).
   * @param parentId        id of the whole payment (if using multi-part, there will be N associated child payments,
   *                        each with a different id).
   * @param externalId      externally-controlled identifier (to reconcile between application DB and eclair DB).
   * @param paymentHash     payment hash.
   * @param finalAmount     amount that should be received by the final recipient (usually from a Bolt 11 invoice).
   * @param recipientNodeId id of the final recipient.
   * @param upstream        information about the payment origin (to link upstream to downstream when relaying a payment).
   * @param paymentRequest  Bolt 11 invoice.
   * @param storeInDb       whether to store data in the payments DB (e.g. when we're relaying a trampoline payment, we
   *                        don't want to store in the DB).
   * @param publishEvent    whether to publish a [[fr.acinq.eclair.payment.PaymentEvent]] on success/failure (e.g. for
   *                        multi-part child payments, we don't want to emit events for each child, only for the whole payment).
   * @param additionalHops  additional hops that the payment state machine isn't aware of (e.g. when using trampoline, hops
   *                        that occur after the first trampoline node).
   */
  case class SendPaymentConfig(id: UUID,
                               parentId: UUID,
                               externalId: Option[String],
                               paymentHash: ByteVector32,
                               finalAmount: MilliSatoshi,
                               recipientNodeId: PublicKey,
                               upstream: Upstream,
                               paymentRequest: Option[PaymentRequest],
                               storeInDb: Boolean, // e.g. for trampoline we don't want to store in the DB when we're relaying payments
                               publishEvent: Boolean,
                               additionalHops: Seq[NodeHop]) {
    def fullRoute(hops: Seq[ChannelHop]): Seq[Hop] = hops ++ additionalHops
  }

}
