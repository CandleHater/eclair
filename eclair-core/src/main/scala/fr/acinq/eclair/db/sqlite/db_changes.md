# DB changes for MPP and Trampoline

Avec MPP et Trampoline, la structure d'un payement change beaucoup.
C'est l'occasion de changer nos schéma DB pour bien représenter ça.
Voilà un résumé de ce qu'on a aujourd'hui et quelques propositions.
Il faut qu'on se mette d'accord avant que je change le code et que j'écrive les migrations, pour
s'assurer qu'on galèrera pas avec nos déploiements.

Le mail est un peu long, mais ça serait encore plus compliqué de reviewer ça au milieu d'une PR qui
contiendra plein de code, mieux vaut se mettre d'accord sur le schéma avant tout !

Et c'est aussi l'occasion de rajouter certains champs s'il y en a qui seraient utiles pour Phoenix
ou Eclair-Mobile, histoire de minimiser les migrations.

Les principales difficultés que je vois, c'est:

* Il y a deux types de fees:
  * La fee trampoline qu'on calcule au début, pour le montant total (indépendant de MPP)
  * Pour chacune des parties du payement MPP, une fee de routing pour atteindre le noeud trampoline
* Les payements MPP ont plusieurs from_channel_id et plusieurs to_channel_id
* Ça serait bien d'éviter autant que possible d'utiliser des BLOB encodés qui rendent le traitement
  uniquement en query SQL compliqué

Je mets en gras à chaque fois la proposition que je préfère.

## AuditDb

On a 3 tables concernées: `sent`, `received`, `relayed`.

### Table `sent`

+--------------------------------------------------------------------------------------------+
| sent                                                                                       |
+-------------+-----------+--------------+------------------+---------------+-----------+----+
| amount_msat | fees_msat | payment_hash | payment_preimage | to_channel_id | timestamp | id |
+-------------+-----------+--------------+------------------+---------------+-----------+----+

Pour l'instant, dans le cas d'un payement MPP qui contient N payements, on écrit N lignes dans
cette table. Pour l'instant c'est pas clair ce qui est compris dans `fees_msat` (est-ce que ça
inclut la fee trampoline ?).

Deux propositions possibles. Soit on préfère une seule ligne pour le payement complet, dans ce cas:

+-----------------------------------------------------------------------------------------------------+
| sent                                                                                                |
+-------------+-----------+------------+--------------+------------------+----------------+-----------+
| amount_msat | fees_msat | payment_id | payment_hash | payment_preimage | target_node_id | timestamp |
+-------------+-----------+------------+--------------+------------------+----------------+-----------+

Dans ce cas `amount_msat` contient le montant que va recevoir le destinataire final (pas le noeud
trampoline), et `fees_msat` contient la totalité des fees payées (les routing fees MPP + la fee
trampoline). La somme des HTLCs qu'on a envoyés vaut `amount_msat + fees_msat`. On perd de
l'information (mais qu'on peut retrouver dans PaymentsDb).

Soit on veut garder une ligne par HTLC ce qui permet de vraiment regarder les flux dans nos
channels en détail, dans ce cas:

+-------------------------------------------------------------------------------------------------------------------------------------------------------------+
| sent                                                                                                                                                        |
+-------------+-----------+-------------------+------------+-------------------+--------------+------------------+----------------+---------------+-----------+
| amount_msat | fees_msat | final_amount_msat | payment_id | parent_payment_id | payment_hash | payment_preimage | target_node_id | to_channel_id | timestamp |
+-------------+-----------+-------------------+------------+-------------------+--------------+------------------+----------------+---------------+-----------+

Dans ce cas `final_amount_msat` représente le montant total que recevra le destinataire final.
Le montant du HTLC sortant sera `amount_msat + fees_msat`.
On peut déduire la fee trampoline en groupant sur `parent_payment_id` et faisant `sum(amount_msat) - final_amount_msat`.

### Table `received`

+----------------------------------------------------------+
| received                                                 |
+-------------+--------------+-----------------+-----------+
| amount_msat | payment_hash | from_channel_id | timestamp |
+-------------+--------------+-----------------+-----------+

Pour l'instant pour un payement MPP qui contient N payements, on écrit N lignes dans
cette table.

Pareil que pour `sent`, deux propositions possibles en fonctione du niveau de détail qu'on veut
garder. Soit on préfère une seule ligne pour le payement complet, dans ce cas:

+----------------------------------------+
| received                               |
+-------------+--------------+-----------+
| amount_msat | payment_hash | timestamp |
+-------------+--------------+-----------+

Soit on veut garder une ligne par HTLC ce qui permet de vraiment regarder les flux dans nos
channels en détail, dans ce cas on ne change rien.

### Table `relayed`

+-----------------------------------------------------------------------------------------------+
| relayed                                                                                       |
+----------------+-----------------+--------------+-----------------+---------------+-----------+
| amount_in_msat | amount_out_msat | payment_hash | from_channel_id | to_channel_id | timestamp |
+----------------+-----------------+--------------+-----------------+---------------+-----------+

Avec Trampoline + MPP, on a maintenant `N` payements entrants pour `M` payements sortants.

On peut garder une seule ligne par payement relayé, mais ça nécessite d'encoder des listes de
`channel_id` (donc ça sera plus compliqué d'analyser la DB avec seulement du SQL).

Ça peut donner ça :

+-------------------------------------------------------------------------------------------------+
| relayed                                                                                         |
+----------------+-----------------+--------------+------------------+----------------+-----------+
| amount_in_msat | amount_out_msat | payment_hash | from_channel_ids | to_channel_ids | timestamp |
+----------------+-----------------+--------------+------------------+----------------+-----------+

Dans ce cas `from_channel_ids` et `to_channel_ids` sont des listes de `channel_id`. Par contre on
perd l'info de répartition de montants entre les channels. Pour la conserver, on pourrait faire ça :

+-----------------------------------------------------------------------------------+
| relayed                                                                           |
+----------------+-----------------+--------------+----------+----------+-----------+
| amount_in_msat | amount_out_msat | payment_hash | incoming | outgoing | timestamp |
+----------------+-----------------+--------------+----------+----------+-----------+

Où `incoming` et `outgoing` seraient des listes de `(amount_msat,channel_id)`.

Dernière option, qui permettrait de garder les données sans avoir à encoder des listes :

+-----------------------------------------------------------------+
| relayed                                                         |
+--------------+-------------+------------+-----------+-----------+
| payment_hash | amount_msat | channel_id | direction | timestamp |
+--------------+-------------+------------+-----------+-----------+

Où on aurait `N+M` lignes par payement relayé, avec `direction` qui serait `IN` ou `OUT`.
Il suffit de filtrer par `payment_hash` pour obtenir tout ce dont on a besoin.

## PaymentsDb

On a 2 tables concernées: `sent_payments` et `received_payments`.

### Table `sent_payments`

+------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| sent_payments                                                                                                                                                                      |
+----+-----------+-------------+--------------+-------------+----------------+------------+-----------------+--------------+------------------+-----------+---------------+----------+
| id | parent_id | external_id | payment_hash | amount_msat | target_node_id | created_at | payment_request | completed_at | payment_preimage | fees_msat | payment_route | failures |
+----+-----------+-------------+--------------+-------------+----------------+------------+-----------------+--------------+------------------+-----------+---------------+----------+

On crée une ligne par HTLC envoyé. Au moment du fulfill/fail, on met à jour les lignes concernées.
Ce qui va pas c'est qu'avec Trampoline, c'est compliqué de répartir la fee trampoline d'une manière
cohérente entre les payements (surtout qu'au moment où on envoie un HTLC, on sait pas encore en
combien de HTLC on aura divisé le payement).

Comme pour la table `sent` d'AuditDb, voilà ce que je propose (ici on peut pas se contenter d'une
seule ligne pour tout le payement, on est obligé de suivre chaque HTLC):

+--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| sent_payments                                                                                                                                                                                          |
+----+-----------+-------------+--------------+------------------+-------------+-----------+-------------------+----------------+-----------------+---------------+----------+------------+--------------+
| id | parent_id | external_id | payment_hash | payment_preimage | amount_msat | fees_msat | final_amount_msat | target_node_id | payment_request | payment_route | failures | created_at | completed_at |
+----+-----------+-------------+--------------+------------------+-------------+-----------+-------------------+----------------+-----------------+---------------+----------+------------+--------------+

Le montant du HTLC sortant est `amount_msat + fees_msat`. On peut déduire la fee trampoline en
groupant sur `parent_id` et en faisant `sum(amount_msat) - final_amount_msat`.
En cas de succès, la route contiendra à la fois la route Trampoline et la route jusqu'au Trampoline.
Ça ressemblera à ça : `List(ChannelHop(a,b,channel_id_ab), ChannelHop(b,c,channel_id_bc), NodeHop(c,d), NodeHop(d,e))`.

### Table `received_payments`

+----------------------------------------------------------------------------------------------------------+
| received_payments                                                                                        |
+--------------+------------------+-----------------+---------------+------------+-----------+-------------+
| payment_hash | payment_preimage | payment_request | received_msat | created_at | expire_at | received_at |
+--------------+------------------+-----------------+---------------+------------+-----------+-------------+

Lorsqu'on génère une invoice, on crée une ligne dans cette table avec `received_msat = 0`.
On met à jour `received_msat` lorsqu'on reçoit le montant attendu.
À mon avis pas besoin de changer quoi que ce soit dans cette table, cela fonctionne bien.

Ça va finalement ça faisait que 175 lignes :)
Dites-moi ce que vous en pensez, je pense que ça vaut le coup de prendre un peu de temps pour faire
ça bien avant de se plonger dans le code.

Bastien