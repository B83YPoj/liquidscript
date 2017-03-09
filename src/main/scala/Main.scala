
import com.ning.http.client.cookie.Cookie
import dispatch.{Http, url}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json}
import scorex.account.AddressScheme
import scorex.crypto.encode.{Base16, Base58}
import scorex.crypto.hash.Sha256
import scorex.transaction.Transaction
import scorex.transaction.assets.TransferTransaction
import scorex.utils._
import scorex.wallet.Wallet

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
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
  val broadcastedTransactions: ArrayBuffer[TransferTransaction] = ArrayBuffer[TransferTransaction]()

  val seed = args(0)
  val email = args(1)
  val password = args(2)

  val wallet = new Wallet(None, Base58.encode(Sha256(seed)), Some(seed.getBytes))
  val myAddress = wallet.generateNewAccount().get

  log.info("Start script with address " + myAddress.address)

  loop(NTP.correctedTime() - 60000, (login(), NTP.correctedTime()))

  @tailrec
  def loop(lastTimestamp: Long, cookie: (Cookie, Long)): Unit = {
    val currentTime = NTP.correctedTime()
    val parsedResp = getLiquidProTransactions(lastTimestamp, cookie, currentTime)
    if ((parsedResp \ "success").as[Boolean]) {
      val transactionJss = (parsedResp \ "data").as[List[JsValue]]
      log.info("Got liquid.pro transactions: " + transactionJss.toString)
      transactionJss.foreach { tjs =>
        val tx = txFromJs(tjs)
        broadcastedTransactions += tx
        broadcastTransaction(tx)
      }
    } else {
      log.error("Incorrect response: " + parsedResp)
    }

    Thread.sleep(60000)

    checkBroadcasted()

    val newCookie = if (NTP.correctedTime() - cookie._2 < 7 * 24 * 60 * 1000) cookie
    else (login(), NTP.correctedTime())
    loop(currentTime, newCookie)
  }

  def txFromJs(tjs: JsValue): TransferTransaction = {
    val hash = (tjs \ "hash").as[String]
    val timestamp = DateTime.parse((tjs \ "time").as[String], formatter.withZone(DateTimeZone.UTC)).getMillis
    val attachment:Array[Byte] = Array(0: Byte) ++ Base16.decode(hash)
    attachmentTransactions(attachment, timestamp)
  }

  def getLiquidProTransactions(lastTimestamp: Long, cookie: (Cookie, Long), currentTime: Long): JsValue = {
    val from = new DateTime(lastTimestamp).toDateTime.withZone(DateTimeZone.forID("Europe/Moscow"))
      .toString("yyyy-MM-dd HH:mm:ss")
    val to = new DateTime(currentTime).toDateTime.withZone(DateTimeZone.forID("Europe/Moscow"))
      .toString("yyyy-MM-dd HH:mm:ss")
    val body = "{dateFrom: \"" + from + "\", dateTo: \"" + to + "\"}"
    val resp = liquidProPostRequest("/api/blockchain/GetQuotesLog", body, Some(cookie._1))
    Json.parse(resp.getResponseBody)
  }

  //keep transactions in local cache until they have enough confirmations
  def checkBroadcasted(): Unit = Try {
    val height = (wavesGetRequest("/blocks/height") \ "height").as[Int]
    broadcastedTransactions.foreach { tx =>
      val resp = wavesGetRequest("/transactions/info/" + Base58.encode(tx.id))
      (resp \ "height").asOpt[Int] match {
        case None =>
          log.info(s"Transaction ${Base58.encode(tx.id)} is not in blockchain")
          broadcastTransaction(tx)
        case Some(h) =>
          log.info(s"Transaction ${Base58.encode(tx.id)} have ${height - h} confirmations")
          if (height - h > 10) {
            broadcastedTransactions -= tx
          }
      }
    }
  }

  def login(): Cookie = {
    val loginResponse = liquidProPostRequest("/api/account/login",
      "{\"email\": \"" + email + "\",\"password\": \"" + password + "\"}",
      None)
    require((Json.parse(loginResponse.getResponseBody) \ "success").as[Boolean])
    log.info("Successful login")
    loginResponse.getCookies.asScala.head
  }

  def broadcastTransaction(tx: TransferTransaction) = {
    val resp = wavesPostRequest("/assets/broadcast/transfer", Map(), tx.json.toString())
    log.info(s"Transaction ${tx.json} broadcasted: " + resp)
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
    val request = Http(url(wavesPeer + us).GET)
    val response = Await.result(request, 10.seconds)
    Json.parse(response.getResponseBody)
  }

  def wavesPostRequest(us: String,
                       params: Map[String, String] = Map.empty,
                       body: String = ""): JsValue = {
    val request = Http(url(wavesPeer + us).POST << params << body)
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