
import com.ning.http.client.Response
import com.ning.http.client.cookie.Cookie
import com.typesafe.config.ConfigFactory
import dispatch.{Http, url}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import play.api.libs.json.{JsValue, Json}
import scorex.account.{Account, AddressScheme}
import scorex.crypto.encode.Base58
import scorex.crypto.hash.{CryptographicHash, ScorexHashChain, SecureCryptographicHash, Sha256}
import scorex.transaction.assets.TransferTransaction
import scorex.utils._
import scorex.wallet.Wallet

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try

object Main extends App with ScorexLogging {

  val formatter: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss")
  AddressScheme.current = new AddressScheme {
    val chainId: Byte = 87.toByte
  }
  val liquidProURL = "http://test.liquid.pro"
  val wavesPeer: String = "https://nodes.wavesnodes.com"

  val Headers: Map[String, String] = Map()
  val seed = args(0)
  val email = args(1)
  val password = args(2)

  val wallet = new Wallet(None, Base58.encode(Sha256(seed)), Some(seed.getBytes))
  val myAddress = wallet.generateNewAccount().get

  log.info("Start script with address " + myAddress.address)

  //todo relogin at most once a month
  val loginResponse = liquidProPostRequest("/api/account/login",
    "{\"email\": \"" + email + "\",\"password\": \"" + password + "\"}",
    None)
  require((Json.parse(loginResponse.getResponseBody) \ "success").as[Boolean])
  val loginCookies: Cookie = loginResponse.getCookies.asScala.head
  log.info("Successful login")

  loop(NTP.correctedTime() - 60000)


  @tailrec
  def loop(lastTimestamp: Long): Unit = {
    val currentTime = NTP.correctedTime()
    val from = new DateTime(lastTimestamp).toDateTime.toString("yyyy-MM-dd HH:mm:ss")
    val to = new DateTime(currentTime).toDateTime.toString("yyyy-MM-dd HH:mm:ss")
    val body = "{dateFrom: \"" + from + "\", dateTo: \"" + to + "\"}"
    val resp = liquidProPostRequest("/api/blockchain/GetQuotesLog", body, Some(loginCookies))
    val parsedResp = Json.parse(resp.getResponseBody)
    if ((parsedResp \ "success").as[Boolean]) {
      val transactionJss = (parsedResp \ "data").as[List[JsValue]]
      log.debug(transactionJss.toString)
      //[{"quoteId":1111,"softQuoteId":1234,"secCode":"BR-3.17M220217CA 55","bidAsk":"Bid","price":2.5,"size":100,"time":"2017-02-13 09:34:40","hash":"9237D7255CA299AFCA7310D0A0494F0F6C5959F2014BF4A0AFFF46B52918B782"},{"quoteId":2222,"softQuoteId":2345,"secCode":"SBRF-3.17M150317PA 1700","bidAsk":"Ask","price":567,"size":1,"time":"2017-02-13 09:34:50","hash":"03D4508EFDED43670FABCA4FEC5500B463457428257070D47E303F9B006FC8C9"}]
      val txs = transactionJss.map { tjs =>
        val hash = (tjs \ "hash").as[String]
        val timestamp = DateTime.parse((tjs \ "time").as[String], formatter).getMillis
        attachmentTransactions(hash.getBytes, timestamp)
      }
      println(txs)
      //!!!!!

    } else {
      log.error("Incorrect response: " + resp)
    }

    Thread.sleep(60000)
    loop(currentTime)
  }

  def attachmentTransactions(attachment: Array[Byte], timestamp: Long): TransferTransaction = {
    TransferTransaction.create(None,
      myAddress,
      myAddress,
      1,
      timestamp: Long,
      None,
      100000,
      attachment: Array[Byte])
  }

  def wavesGetRequest(us: String): JsValue = {
    //todo push to multiple peers
    val request = Http(url(wavesPeer + us).GET <:< Headers)
    val response = Await.result(request, 10.seconds)
    Json.parse(response.getResponseBody)
  }

  def wavesPostRequest(us: String,
                       params: Map[String, String] = Map.empty,
                       body: String = ""): JsValue = {
    val request = Http(url(wavesPeer + us).POST << params <:< Headers << body)
    val response = Await.result(request, 5.seconds)
    Json.parse(response.getResponseBody)
  }

  def liquidProPostRequest(us: String,
                           body: String = "",
                           cookie: Option[Cookie]) = {
    val req0 = url(liquidProURL + us).POST.setBody(body).setHeader("Content-Type", "application/json")
    val request = Http(cookie.map(c => req0.addCookie(c)).getOrElse(req0))
    Await.result(request, 5.seconds)
  }

}