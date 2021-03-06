package com.proinnovate.scalatra.userauth

import com.weiglewilczek.slf4s.Logging
import org.scalatra.{ CookieSupport, ScalatraKernel, CookieOptions, Cookie }
import java.security.SecureRandom

/**
 * Authentication strategy to authenticate a user from a cookie.
 *
 * The basic idea is that a unique random token is generated for the user.  This token is stored as a cookie on the
 * client and recorded by the server.  If a user returns to the site, the token in the cookie is used to recall the
 * user details from the server and the user is logged in automatically.
 *
 * If this system is used on a web site that is not running using SSL then the cookie content will be sent unencrypted
 * and a third party could capture the cookie and use it to log in themselves.  The only restriction on this is that
 * the cookie may timeout after a period of time so there is a delimited window of time within which a stolen cookie
 * can be used.
 *
 * Note that this issue is not unique to cookies and remember me.  The normal way of tracking sessions with session IDs
 * means that session IDs can also be used to copy and use someone else's identity unless they are connecting with SSL.
 *
 */
class RememberMeStrategy[U] extends UserAuthStrategy[U] with Logging {

  logger.debug("Scalatra-UserAuth RememberMe Strategy Initialised")

  /**
   * The cookie token name used to store the users remember me token as a cookie.
   */
  lazy val COOKIE_KEY = "rememberMe"

  /**
   * The name of the checkbox form element that a user ticks on a login form to indicate that they want their login
   * to be remembered between uses.
   */
  lazy val REMEMBERME_CHECKBOX_NAME = "rememberMe"

  /**
   * The length of time the cookie is preserved for before becoming invalid.
   */
  lazy val cookieLifeInSeconds = 7 * 24 * 3600

  /**
   * Determine whether or not the cookie generated should only be used on a SSL secured site.  If set to true then
   * this cookie information will not be transferred between the client and the server in any unsecured connections.
   * This would be strongly recommended if you are using SSL.
   */
  lazy val cookieIsSecure = false

  /**
   * Given the current ScalatraKernel, determine whether the authentication strategy is valid for use at the current
   * time.
   *
   * return true if this authentication scheme can be used at this time.
   */
  final def authIsValid(app: ScalatraKernel): Boolean = {
    val result = app match {
      case a: CookieSupport ⇒
        logger.debug("CookieSupport detected")
        logger.debug("Cookies: " + {
          app.request.getCookies match {
            case null        ⇒ ""
            case x: Array[_] ⇒ x.toList
          }
        })
        // Given that the CookieSupport trait has been mixed in, this authentication method can be used if a cookie
        // with the `COOKIE_KEY` already exists.
        a.cookies.get(COOKIE_KEY).isDefined
      case _ ⇒
        logger.error("The ScalatraKernel must mixin the CookieSupport trait in order to use RememberMe authentication!")
        false
    }
    logger.debug("RememberMeStrategy.authIsValid = " + result)
    result
  }

  /**
   * return Some(User) or None if no user was authenticated.
   */
  final def authenticateUser(app: ScalatraKernel)(implicit validate: (String, String) ⇒ Option[U]): Either[String, U] = {
    app match {
      case x: RememberMeSupport[U] with CookieSupport ⇒
        val tokenStringOpt = x.cookies.get(COOKIE_KEY)
        tokenStringOpt match {
          case Some(RememberMeToken(userId, _)) ⇒
            x.validateRememberMeToken(userId, tokenStringOpt.get).map(u ⇒ Right(u)).getOrElse {
              removeCookieFromClient(x)
              Left("")
            }
          case _ ⇒ Left("")
        }
      case _ ⇒
        // If there is no CookieSupport trait quietly fail, an error message has already been issued by the authIsValid
        // method.
        Left("")
    }
  }

  /**
   * After authentication, sets the remember-me cookie on the response.
   */
  def afterAuthProcessing(app: ScalatraKernel) {
    val checkBoxTicked = checkbox2boolean(app.params.get(REMEMBERME_CHECKBOX_NAME).getOrElse("").toString)
    val (userOpt, userId) = app match {
      case r: UserAuthSupport[U] ⇒
        val userOption = r.userOption
        val userId = userOption.map(r.userIdForUser(_)).getOrElse("")
        (userOption, userId)
      case _ ⇒
        (None, "")
    }
    if (checkBoxTicked && userOpt.isDefined) {
      val token = RememberMeToken(userId)
      val cookie = Cookie(COOKIE_KEY, token.toString)(CookieOptions(secure = cookieIsSecure, maxAge = cookieLifeInSeconds,
        httpOnly = true))
      val cookieString = cookie.toCookieString
      logger.debug("RememberMe cookie being stored: " + cookieString)
      app.response.addHeader("Set-Cookie", cookieString)
      app match {
        case r: RememberMeSupport[U] with UserAuthSupport[U] ⇒
          logger.debug("Storing RememeberMe token for user: %s".format(r.userOption.get))
          r.storeRememberMeTokenForUser(r.userOption.get, Some(token.toString))
        case _ ⇒
          logger.error("The ScalatraKernel must mixin the RememberMeSupport trait in order to provide RememberMe " +
            "authentication!")
          None
      }
    }
  }

  /**
   * Clears the remember-me cookie for the specified user.
   */
  def beforeLogout(app: ScalatraKernel) {
    app match {
      case x: RememberMeSupport[U] with UserAuthSupport[U] with CookieSupport ⇒
        x.userOption.map {
          user ⇒
            // Store a blank token for the user to cancel any existing remember me token.
            x.storeRememberMeTokenForUser(user, None)
        }
        removeCookieFromClient(x)
    }
  }

  // PRIVATE

  /**
   * Used to easily match a checkbox value
   */
  private final def checkbox2boolean(s: String): Boolean = {
    s match {
      case "yes"  ⇒ true
      case "y"    ⇒ true
      case "1"    ⇒ true
      case "true" ⇒ true
      case _      ⇒ false
    }
  }

  private final def removeCookieFromClient(app: CookieSupport) {
    app.cookies.get(COOKIE_KEY) foreach {
      _ ⇒ app.cookies.update(COOKIE_KEY, null)
    }
  }

  private final def generateToken(): String = {
    val random = SecureRandom.getInstance("SHA1PRNG")
    val str = new Array[Byte](16)
    random.nextBytes(str)
    str.toString
  }

  private class RememberMeToken(user: String, prefix: String = generateToken()) {

    override lazy val toString = prefix + "-" + user

  }

  private object RememberMeToken {

    def apply(user: String, prefix: String = generateToken()): RememberMeToken =
      new RememberMeToken(user, prefix)

    def unapply(tokenString: String): Option[(String, String)] = {
      tokenString match {
        case tokenRE(token, user) ⇒ Some(user, token)
        case _                    ⇒ None
      }
    }

    private val tokenRE = """(.+)\-([^\-]+)""".r

  }

}

