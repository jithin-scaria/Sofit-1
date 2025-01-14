/**
Open Bank Project - Sofi Web Application
Copyright (C) 2011 - 2021, TESOBE GmbH.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE GmbH.
Osloer Str. 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */

package code.lib

import code.util.Helper
import code.util.Helper.MdcLoggable
import net.liftweb.common.{Box, Empty, Failure, Full}
import net.liftweb.http.{LiftResponse, S, SessionVar}
import net.liftweb.util.Props
import oauth.signpost.{OAuthConsumer, OAuthProvider}
import oauth.signpost.basic.DefaultOAuthConsumer
import oauth.signpost.signature.HmacSha256MessageSigner

sealed trait Provider {
  val name : String

  val apiBaseUrl : String
  val requestTokenUrl : String
  val accessTokenUrl : String
  val authorizeUrl : String
  val signupUrl : Option[String]

  /**
   * Can't do oAuthProvider = new DefaultOAuthProvider(requestTokenUrl, accessTokenUrl, authorizeUrl)
   * here as the Strings all evaluate at null at this point in object creation
   */
  val oAuthProvider : OAuthProvider

  val consumerKey : String
  val consumerSecret : String
}

trait DefaultProvider extends Provider {
  val name = "The Open Bank Project Demo"
  
  val baseUrl = Props.get("api_hostname", S.hostName)
  val oauthBaseUrlPortal = Props.get("portal.hostname").getOrElse(baseUrl)
  val apiBaseUrl = baseUrl + "/obp"
  val requestTokenUrl = baseUrl + "/oauth/initiate"
  val accessTokenUrl = baseUrl + "/oauth/token"
  val authorizeUrl = oauthBaseUrlPortal + "/oauth/authorize"
  val signupUrl = Some(baseUrl + "/user_mgt/sign_up")

  lazy val oAuthProvider : OAuthProvider = new ObpOAuthProvider(requestTokenUrl, accessTokenUrl, authorizeUrl)

  val consumerKey = Props.get("obp_consumer_key", "")
  val consumerSecret = Props.get("obp_secret_key", "")
}

object OBPDemo extends DefaultProvider

object AddBankAccountProvider extends DefaultProvider {
  override val name = "The Open Bank Project Demo - Add Bank Account"

  //The "login" prefix before /oauth means that we will use the oauth flow that will ask the user
  //to connect a bank account
  override val requestTokenUrl = baseUrl + "/login/oauth/initiate"
  override val accessTokenUrl = baseUrl + "/login/oauth/token"
  override val authorizeUrl = baseUrl + "/login/oauth/authorize"
}

case class Consumer(consumerKey : String, consumerSecret : String) {
  val oAuthConsumer : OAuthConsumer = new DefaultOAuthConsumer(consumerKey, consumerSecret)
}

case class Credential(provider : Provider, consumer : OAuthConsumer, readyToSign : Boolean)

object credentials extends SessionVar[Option[Credential]](None)
object mostRecentLoginAttemptProvider extends SessionVar[Box[Provider]](Empty)

object OAuthClient extends MdcLoggable {

  def getAuthorizedCredential() : Option[Credential] = {
    credentials.filter(_.readyToSign)
  }

  def currentApiBaseUrl : String = {
    getAuthorizedCredential().map(_.provider.apiBaseUrl).getOrElse(OBPDemo.apiBaseUrl)
  }

  def setNewCredential(provider : Provider) : Credential = {
    val consumer = new DefaultOAuthConsumer(provider.consumerKey, provider.consumerSecret)
    val credential = Credential(provider, consumer, false)

    credentials.set(Some(credential))
    credential
  }

  def handleCallback(): Box[LiftResponse] = {

    val success = for {
      verifier <- S.param("oauth_verifier") ?~ "No oauth verifier found"
      provider <- mostRecentLoginAttemptProvider.get ?~ "No provider found for callback"
      consumer <- Box(credentials.map(_.consumer)) ?~ "No consumer found for callback"
    } yield {
      //after this, consumer is ready to sign requests
      provider.oAuthProvider.retrieveAccessToken(consumer, verifier)
      //update the session credentials
      val newCredential = Credential(provider, consumer, true)
      credentials.set(Some(newCredential))
    }

    success match {
      case Full(_) => S.redirectTo("/") //TODO: Allow this redirect to be customised
      case Failure(msg, _, _) => logger.warn(msg)
      case _ => logger.warn("Something went wrong in an oauth callback and there was no error message set for it")
    }
    Empty
  }

  def redirectToOauthLogin() = {
    redirect(OBPDemo)
  }

  private def redirect(provider : Provider) = {
    mostRecentLoginAttemptProvider.set(Full(provider))
    val credential = setNewCredential(provider)
    credential.consumer.setMessageSigner(new HmacSha256MessageSigner())
    val authUrl = provider.oAuthProvider.retrieveRequestToken(credential.consumer, Props.get("base_url", S.hostName) + "/oauthcallback")
    S.redirectTo(authUrl)
  }

  def redirectToConnectBankAccount() = {
    redirect(AddBankAccountProvider)
  }

  def loggedIn : Boolean = credentials.map(_.readyToSign).getOrElse(false)

  def logoutAll() = {
    val apiExplorerHost = {Props.get("base_url", S.hostName)}
    val obpApiHost = { Props.get("api_portal_hostname").or(Props.get("api_hostname")).getOrElse("Unknown") }
    credentials.set(None)
    S.redirectTo(s"$obpApiHost/user_mgt/logout?redirect=$apiExplorerHost")
  }
}
