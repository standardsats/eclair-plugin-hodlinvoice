/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.hodlplugin

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem}
import akka.event.DiagnosticLoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentReceived
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.{MultiPartPaymentFailed, MultiPartPaymentSucceeded}
import fr.acinq.eclair.payment.receive.{MultiPartPaymentFSM, ReceiveHandler}
import fr.acinq.eclair.wire.protocol.IncorrectOrUnknownPaymentDetails
import fr.acinq.eclair.{Logs, NodeParams}
import fr.acinq.hodlplugin.HodlPaymentHandler.CleanupHodlPayment

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

sealed trait CommandResponse
case class CMDResFailure(reason: String) extends CommandResponse
case class HODL_ACCEPT(paymentHash: ByteVector32)
case class HODL_REJECT(paymentHash: ByteVector32)

class HodlPaymentHandler(nodeParams: NodeParams, paymentHandler: ActorRef)(implicit system: ActorSystem) extends ReceiveHandler {
  val hodlingPayments: mutable.Set[MultiPartPaymentSucceeded] = mutable.Set.empty
  implicit val ec = system.dispatcher
  val logger = system.log

  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case mps:MultiPartPaymentSucceeded if !hodlingPayments.contains(mps) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(mps.paymentHash))) {
        log.info("payment is being held")
        hodlingPayments += mps
      }
    case CleanupHodlPayment(mps) =>
      hodlingPayments -= mps
    case cmd: HODL_ACCEPT => hodlingPayments.find(_.paymentHash == cmd.paymentHash) match {
      case None =>
        val resultString = s"hold payment not found paymentHash=${cmd.paymentHash}"
        logger.warning(resultString)
        ctx.sender() ! CMDResFailure("not found")
      case Some(mps) =>
        val resultString = s"accepting held paymentHash=${cmd.paymentHash}"
        logger.info(resultString)
        paymentHandler ! mps // send it back to the payment handler, this time we won't handle it and it will be handled by the default handler fulfilling the payment
        system.scheduler.scheduleOnce(10 seconds) { // remove this MPS from the hodled payments, but wait a bit to give time to fulfill it
          paymentHandler ! CleanupHodlPayment(mps)
        }
        logger.info(resultString)
        ctx.sender() ! CMDResFailure("success")
    }
    case cmd: HODL_REJECT => hodlingPayments.find(_.paymentHash == cmd.paymentHash) match {
        case None =>
          val resultString = s"hold payment not found paymentHash=${cmd.paymentHash}"
          logger.warning(resultString)
          ctx.sender() ! CMDResFailure("not found")
        case Some(mps) =>
          val resultString = s"rejecting held paymentHash=${cmd.paymentHash}"
          logger.warning(resultString)
          val received = PaymentReceived(cmd.paymentHash, mps.parts.map {
            case p: MultiPartPaymentFSM.HtlcPart => PaymentReceived.PartialPayment(p.amount, p.htlc.channelId)
          })
          val mpf = MultiPartPaymentFailed(cmd.paymentHash,
            IncorrectOrUnknownPaymentDetails(received.amount, nodeParams.currentBlockHeight), mps.parts)
          paymentHandler ! mpf
          hodlingPayments -= mps
          ctx.sender() ! CMDResFailure("rejected")
      }
  }

  /*
  def acceptPayment(paymentHash: ByteVector32): String = hodlingPayments.find(_.paymentHash == paymentHash) match {
    case None =>
      val resultString = s"hold payment not found paymentHash=$paymentHash"
      logger.warning(resultString)
      resultString
    case Some(mps) =>
      val resultString = s"accepting held paymentHash=$paymentHash"
      logger.info(resultString)
      paymentHandler ! mps // send it back to the payment handler, this time we won't handle it and it will be handled by the default handler fulfilling the payment
      system.scheduler.scheduleOnce(10 seconds) { // remove this MPS from the hodled payments, but wait a bit to give time to fulfill it
        paymentHandler ! CleanupHodlPayment(mps)
      }
      logger.info(resultString)
      resultString
    case _ =>
      val msg = "something else"
      logger.info(msg)
      msg
  }

  def rejectPayment(paymentHash: ByteVector32): String = hodlingPayments.find(_.paymentHash == paymentHash) match {
    case None =>
      val resultString = s"hold payment not found paymentHash=$paymentHash"
      logger.warning(resultString)
      resultString
    case Some(mps) =>
      val resultString = s"rejecting held paymentHash=$paymentHash"
      logger.warning(resultString)
      val received = PaymentReceived(paymentHash, mps.parts.map {
        case p: MultiPartPaymentFSM.HtlcPart => PaymentReceived.PartialPayment(p.amount, p.htlc.channelId)
      })
      val mpf = MultiPartPaymentFailed(paymentHash, IncorrectOrUnknownPaymentDetails(received.amount, nodeParams.currentBlockHeight ), mps.parts)
      paymentHandler ! mpf
      hodlingPayments -= mps
      resultString
  }
  */

  def acceptPayment(paymentHash: ByteVector32): Future[CMDResFailure] = {
      val resultString = s"hold payment not found paymentHash=$paymentHash"
      logger.warning(resultString)
      Future(CMDResFailure(resultString))
  }

  def rejectPayment(paymentHash: ByteVector32): Future[CMDResFailure] = {
      val resultString = s"hold payment not found paymentHash=$paymentHash"
      logger.warning(resultString)
      Future(CMDResFailure(resultString))
  }
}

object HodlPaymentHandler {

  case class CleanupHodlPayment(mps: MultiPartPaymentSucceeded)

}
