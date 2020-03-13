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

import akka.actor.Actor.Receive
import akka.actor.{ActorContext, ActorRef, ActorSystem, PoisonPill, Status}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel, ChannelCommandResponse}
import fr.acinq.eclair.db.{IncomingPayment, IncomingPaymentStatus, IncomingPaymentsDb}
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.receive.MultiPartHandler.{GetPendingPayments, PendingPayments, ReceivePayment}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.MultiPartPaymentSucceeded
import fr.acinq.eclair.payment.receive.{MultiPartPaymentFSM, ReceiveHandler}
import fr.acinq.eclair.payment.relay.CommandBuffer
import fr.acinq.eclair.payment.{IncomingPacket, PaymentReceived, PaymentRequest}
import fr.acinq.eclair.wire.IncorrectOrUnknownPaymentDetails
import fr.acinq.eclair.{CltvExpiry, Features, Logs, MilliSatoshi, NodeParams, randomBytes32}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class HodlPaymentHandler(nodeParams: NodeParams, system: ActorSystem, db: IncomingPaymentsDb, commandBuffer: ActorRef) extends ReceiveHandler {

  import HodlPaymentHandler._

  val hodlingPayments: mutable.Set[HodlingPayment] = mutable.Set.empty

  // NB: this is safe because this handler will be called from within an actor
  private var pendingPayments: Map[ByteVector32, (ByteVector32, ActorRef)] = Map.empty

  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops, fallbackAddress_opt, paymentPreimage_opt, paymentType) =>
      Try {
        val paymentPreimage = paymentPreimage_opt.getOrElse(randomBytes32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        // We currently only optionally support payment secrets (to allow legacy clients to pay invoices).
        // Once we're confident most of the network has upgraded, we should switch to mandatory payment secrets.
        val features = {
          val f1 = Seq(Features.PaymentSecret.optional, Features.VariableLengthOnion.optional)
          val allowMultiPart = Features.hasFeature(nodeParams.features, Features.BasicMultiPartPayment)
          val f2 = if (allowMultiPart) Seq(Features.BasicMultiPartPayment.optional) else Nil
          val f3 = if (nodeParams.enableTrampolinePayment) Seq(Features.TrampolinePayment.optional) else Nil
          Some(PaymentRequest.Features(f1 ++ f2 ++ f3: _*))
        }
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress_opt, expirySeconds = Some(expirySeconds), extraHops = extraHops, features = features)
        log.debug("generated payment request={} from amount={}", PaymentRequest.write(paymentRequest), amount_opt)
        db.addIncomingPayment(paymentRequest, paymentPreimage, paymentType)
        paymentRequest
      } match {
        case Success(paymentRequest) => ctx.sender ! paymentRequest
        case Failure(exception) => ctx.sender ! Status.Failure(exception)
      }

    case p: IncomingPacket.FinalPacket =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(p.add.paymentHash))) {
        db.getIncomingPayment(p.add.paymentHash) match {
          case Some(record) => validatePayment(p, record, nodeParams.currentBlockHeight) match {
            case Some(cmdFail) =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, Tags.FailureType(cmdFail)).increment()
              commandBuffer ! CommandBuffer.CommandSend(p.add.channelId, cmdFail)
            case None =>
              log.info("received payment for amount={} totalAmount={}", p.add.amountMsat, p.payload.totalAmount)
              pendingPayments.get(p.add.paymentHash) match {
                case Some((_, handler)) =>
                  handler ! MultiPartPaymentFSM.HtlcPart(p.payload.totalAmount, p.add)
                case None =>
                  val handler = ctx.actorOf(MultiPartPaymentFSM.props(nodeParams, p.add.paymentHash, p.payload.totalAmount, ctx.self))
                  handler ! MultiPartPaymentFSM.HtlcPart(p.payload.totalAmount, p.add)
                  pendingPayments = pendingPayments + (p.add.paymentHash -> (record.paymentPreimage, handler))
              }
          }
          case None =>
            Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, "InvoiceNotFound").increment()
            val cmdFail = CMD_FAIL_HTLC(p.add.id, Right(IncorrectOrUnknownPaymentDetails(p.payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
            commandBuffer ! CommandBuffer.CommandSend(p.add.channelId, cmdFail)
        }
      }

    case MultiPartPaymentFSM.MultiPartPaymentFailed(paymentHash, failure, parts) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, failure.getClass.getSimpleName).increment()
        log.warning("payment with paidAmount={} failed ({})", parts.map(_.amount).sum, failure)
        pendingPayments.get(paymentHash).foreach { case (_, handler: ActorRef) => handler ! PoisonPill }
        parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => commandBuffer ! CommandBuffer.CommandSend(p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(failure), commit = true))
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case s@MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.info("received complete payment for amount={}", parts.map(_.amount).sum)
        pendingPayments.get(paymentHash).foreach {
          case (preimage: ByteVector32, handler: ActorRef) =>
            handler ! PoisonPill
            pendingPayments = pendingPayments - paymentHash
            hodlingPayments += HodlingPayment(preimage, s)
        }
      }

    case MultiPartPaymentFSM.ExtraPaymentReceived(paymentHash, p, failure) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        failure match {
          case Some(failure) => p match {
            case p: MultiPartPaymentFSM.HtlcPart => commandBuffer ! CommandBuffer.CommandSend(p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(failure), commit = true))
          }
          case None => p match {
            // NB: this case shouldn't happen unless the sender violated the spec, so it's ok that we take a slightly more
            // expensive code path by fetching the preimage from DB.
            case p: MultiPartPaymentFSM.HtlcPart => db.getIncomingPayment(paymentHash).foreach(record => {
              commandBuffer ! CommandBuffer.CommandSend(p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, record.paymentPreimage, commit = true))
              val received = PaymentReceived(paymentHash, PaymentReceived.PartialPayment(p.amount, p.htlc.channelId) :: Nil)
              db.receiveIncomingPayment(paymentHash, p.amount, received.timestamp)
              ctx.system.eventStream.publish(received)
            })
          }
        }
      }

    case GetPendingPayments => ctx.sender ! PendingPayments(pendingPayments.keySet)

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case ChannelCommandResponse.Ok => // ignoring responses from channels
  }

  def acceptPayment(paymentHash: ByteVector32): String = {
    hodlingPayments.find(_.p.paymentHash == paymentHash) match {
      case None =>
        val resultString = s"hodl-payment not found paymentHash=$paymentHash"
        system.log.warning(resultString)
        resultString
      case Some(payment) =>
        val resultString = s"accepting hodled paymentHash=$paymentHash"
        system.log.info(resultString)
        val received = PaymentReceived(paymentHash, payment.p.parts.map {
          case p: MultiPartPaymentFSM.HtlcPart => PaymentReceived.PartialPayment(p.amount, p.htlc.channelId)
        })
        db.receiveIncomingPayment(paymentHash, received.amount, received.timestamp)
        payment.p.parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => commandBuffer ! CommandBuffer.CommandSend(p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, payment.preimage, commit = true))
        }
        system.eventStream.publish(received)
        hodlingPayments -= payment
        resultString
    }
  }

  def rejectPayment(paymentHash: ByteVector32): String = {
    hodlingPayments.find(_.p.paymentHash == paymentHash) match {
      case None =>
        val resultString = s"hodl-payment not found paymentHash=$paymentHash"
        system.log.warning(resultString)
        resultString
      case Some(payment) =>
        val resultString = s"rejecting hodled paymentHash=$paymentHash"
        system.log.info(resultString)
        payment.p.parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => commandBuffer ! CommandBuffer.CommandSend(p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(IncorrectOrUnknownPaymentDetails(p.totalAmount, nodeParams.currentBlockHeight)), commit = true))
        }
        hodlingPayments -= payment
        resultString
    }
  }
}

object HodlPaymentHandler {

  private def validatePayment(payment: IncomingPacket.FinalPacket, record: IncomingPayment, currentBlockHeight: Long)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val cmdFail = CMD_FAIL_HTLC(payment.add.id, Right(IncorrectOrUnknownPaymentDetails(payment.payload.totalAmount, currentBlockHeight)), commit = true)
    val paymentAmountOk = record.paymentRequest.amount.forall(a => validatePaymentAmount(payment, a))
    val paymentCltvOk = validatePaymentCltv(payment, record.paymentRequest.minFinalCltvExpiryDelta.getOrElse(Channel.MIN_CLTV_EXPIRY_DELTA).toCltvExpiry(currentBlockHeight))
    val paymentStatusOk = validatePaymentStatus(payment, record)
    val paymentFeaturesOk = validateInvoiceFeatures(payment, record.paymentRequest)
    if (paymentAmountOk && paymentCltvOk && paymentStatusOk && paymentFeaturesOk) None else Some(cmdFail)
  }

  private def validatePaymentStatus(payment: IncomingPacket.FinalPacket, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    if (record.status.isInstanceOf[IncomingPaymentStatus.Received]) {
      log.warning("ignoring incoming payment for which has already been paid")
      false
    } else if (record.paymentRequest.isExpired) {
      log.warning("received payment for expired amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePaymentAmount(payment: IncomingPacket.FinalPacket, expectedAmount: MilliSatoshi)(implicit log: LoggingAdapter): Boolean = {
    // The total amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
    // it must not be greater than two times the requested amount.
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
    if (payment.payload.totalAmount < expectedAmount) {
      log.warning("received payment with amount too small for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else if (payment.payload.totalAmount > expectedAmount * 2) {
      log.warning("received payment with amount too large for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePaymentCltv(payment: IncomingPacket.FinalPacket, minExpiry: CltvExpiry)(implicit log: LoggingAdapter): Boolean = {
    if (payment.add.cltvExpiry < minExpiry) {
      log.warning("received payment with expiry too small for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validateInvoiceFeatures(payment: IncomingPacket.FinalPacket, pr: PaymentRequest)(implicit log: LoggingAdapter): Boolean = {
    if (payment.payload.amount < payment.payload.totalAmount && !pr.features.allowMultiPart) {
      log.warning("received multi-part payment but invoice doesn't support it for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else if (payment.payload.amount < payment.payload.totalAmount && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning("received multi-part payment with invalid secret={} for amount={} totalAmount={}", payment.payload.paymentSecret, payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else if (payment.payload.paymentSecret.isDefined && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning("received payment with invalid secret={} for amount={} totalAmount={}", payment.payload.paymentSecret, payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  case class HodlingPayment(preimage: ByteVector32, p: MultiPartPaymentSucceeded)
}
