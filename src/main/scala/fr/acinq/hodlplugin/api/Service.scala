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

package fr.acinq.hodlplugin.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.Credentials
import com.typesafe.config.Config
import fr.acinq.eclair.api.ExtraDirectives
import fr.acinq.hodlplugin.handler.HodlPaymentHandler

import scala.concurrent.Future
import scala.concurrent.duration._

class Service(conf: Config, system: ActorSystem, hodlPaymentHandler: HodlPaymentHandler) extends ExtraDirectives {

  val password = conf.getString("api.password")
  val apiHost = conf.getString("api.binding-ip")
  val apiPort = {
    if(conf.hasPath("hodlplugin.api.port"))
      conf.getInt("hodlplugin.api.port")
    else
      conf.getInt("api.port") + 1 // if no port was specified we default to eclair api port + 1
  }

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = system.scheduler)(Future.successful(None))(system.dispatcher) // force a 1 sec pause to deter brute force
  }

  val route: Route = authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
    post {
      formField(paymentHashFormParam) { ph =>
        path("hodlaccept") {
          complete(hodlPaymentHandler.acceptPayment(ph))
        } ~ path("hodlreject") {
          complete(hodlPaymentHandler.rejectPayment(ph))
        }
      }
    }
  }



}
