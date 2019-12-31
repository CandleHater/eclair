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
          case Some(paymentSecret) => r.predefinedRoute match {
            case Nil => spawnMultiPartPaymentFsm(paymentCfg) forward SendMultiPartPayment(paymentSecret, r.recipientNodeId, r.finalAmount, finalExpiry, r.maxAttempts, r.assistedRoutes, r.routeParams)
            case hops => spawnPaymentFsm(paymentCfg) forward SendPaymentToRoute(hops, Onion.createMultiPartPayload(r.finalAmount, invoice.amount.getOrElse(r.finalAmount), finalExpiry, paymentSecret))
          }
          case None => sender ! PaymentFailed(paymentId, r.paymentHash, LocalFailure(new IllegalArgumentException("can't send payment: multi-part invoice is missing a payment secret")) :: Nil)
        }
        case _ =>
          val payFsm = spawnPaymentFsm(paymentCfg)
          // NB: we only generate legacy payment onions for now for maximum compatibility.
          r.predefinedRoute match {
            case Nil => payFsm forward SendPayment(r.recipientNodeId, FinalLegacyPayload(r.finalAmount, finalExpiry), r.maxAttempts, r.assistedRoutes, r.routeParams)
            case hops => payFsm forward SendPaymentToRoute(hops, FinalLegacyPayload(r.finalAmount, finalExpiry))
          }
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
   * @param predefinedRoute  (optional) route to use for the payment.
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
                                predefinedRoute: Seq[PublicKey] = Nil,
                                assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                                routeParams: Option[RouteParams] = None) {
    // We add one block in order to not have our htlcs fail when a new block has just been found.
    def finalExpiry(currentBlockHeight: Long) = finalExpiryDelta.toCltvExpiry(currentBlockHeight + 1)
  }

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
