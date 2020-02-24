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
package fr.acinq.eclair.hodlinvoice

import akka.actor.{ActorRef, ActorSystem}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.CMD_FAIL_HTLC
import fr.acinq.eclair.db.IncomingPaymentsDb
import fr.acinq.eclair.hodlinvoice.HodlPaymentHandler.HodlingPayment
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.MultiPartPaymentSucceeded
import fr.acinq.eclair.payment.receive.{MultiPartHandler, MultiPartPaymentFSM}
import fr.acinq.eclair.payment.relay.CommandBuffer
import fr.acinq.eclair.wire.TemporaryNodeFailure

import scala.collection.mutable

class HodlPaymentHandler(nodeParams: NodeParams, commandBuffer: ActorRef, db: IncomingPaymentsDb) extends MultiPartHandler(nodeParams, db, commandBuffer) {

  val hodlingPayments: mutable.Set[HodlingPayment] = mutable.Set.empty

  override def doFulfill(system: ActorSystem, preimage: ByteVector32, e: MultiPartPaymentFSM.MultiPartPaymentSucceeded)(implicit log: LoggingAdapter): Unit = {
    log.info(s"hodling incoming payment_hash=${e.paymentHash}")
    hodlingPayments += HodlingPayment(preimage, e)
  }

  def acceptPayment(paymentHash: ByteVector32)(implicit system: ActorSystem): String = {
    hodlingPayments.find(_.p.paymentHash == paymentHash) match {
      case None =>
        system.log.error(s"not releasing payment_hash=$paymentHash not found!")
        s"not releasing payment_hash=$paymentHash not found!"
      case Some(p@HodlingPayment(preimage, e)) =>
        system.log.info(s"releasing hodled payment_hash=$paymentHash")
        super.doFulfill(system, preimage, e)(system.log)
        hodlingPayments -= HodlingPayment(preimage, e)
        s"releasing hodled payment_hash=$paymentHash"
    }
  }

  def rejectPayment(paymentHash: ByteVector32)(implicit system: ActorSystem) = {
    hodlingPayments.find(_.p.paymentHash == paymentHash) match {
      case None =>
        system.log.error(s"not aborting payment_hash=$paymentHash not found!")
        s"not aborting payment_hash=$paymentHash not found!"
      case Some(p@HodlingPayment(_, e)) =>
        system.log.info(s"aborting hodled payment_hash=$paymentHash")
        e.parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => commandBuffer ! CommandBuffer.CommandSend(p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(TemporaryNodeFailure), commit = true))
        }
        s"aborted hodled payment_hash=$paymentHash"
    }
  }

}

object HodlPaymentHandler {

  // @formatter off
  case class ReleasePayment(paymentHash: ByteVector32)
  case class HodlingPayment(preimage: ByteVector32, p: MultiPartPaymentSucceeded)
  // @formatter on

}
