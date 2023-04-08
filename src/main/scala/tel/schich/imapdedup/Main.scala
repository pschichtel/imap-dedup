package tel.schich.imapdedup

import javax.mail.Session
import java.util.Properties
import scala.collection.mutable.Stack
import scala.collection.mutable.ArrayBuffer
import javax.mail.Folder
import javax.mail.Header
import javax.mail.Message
import scala.collection.JavaConverters._
import scala.collection.parallel.CollectionConverters._
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.io.InputStream
import java.io.ByteArrayOutputStream
import org.apache.commons.mail.MultiPartEmail
import javax.mail.Multipart
import scala.annotation.tailrec
import com.sun.mail.imap.IMAPNestedMessage
import scala.util.Try
import java.util.Date
import java.text.DateFormat
import java.text.SimpleDateFormat

def md5(data: Array[Byte]): String = {
  val digest = MessageDigest.getInstance("md5")
  val rawHash = digest.digest(data)
  rawHash.map("%02X" format _).mkString
}

def readAllBytes(is: InputStream): Array[Byte] = {
    val out = ByteArrayOutputStream()
    val buffer = Array.ofDim[Byte](4096)
    var bytesRead = is.read(buffer)

    @tailrec
    def process(): Array[Byte] = {
      val bytesRead = is.read(buffer)
      if (bytesRead != -1) {
        out.write(buffer, 0, bytesRead)
        process()
      } else out.toByteArray()
    }

    process()
}

def convertToBytes(content: Any): Array[Byte] = content match {
  case s: String => s.getBytes(StandardCharsets.UTF_8)
  case is: InputStream => readAllBytes(is)
  case mp: Multipart => {
    (0 until mp.getCount())
      .map(i => convertToBytes(Try(mp.getBodyPart(i).getContent()).getOrElse(null)))
      .reduce(_ ++ _)
  }
  case nestedMessage: IMAPNestedMessage => convertToBytes(Try(nestedMessage.getContent()).getOrElse(null))
  case null => Array.empty
}

def hashContent(content: Any): String = md5(convertToBytes(content))

case class PreprocessedMessage(folderName: String, headers: Map[String, Seq[String]], contentHash: String, subject: Option[String], sentAt: Option[Date], receivedAt: Option[Date])

@main def main: Unit = {
  val sessionProps = Properties()
  sessionProps.put("mail.imap.starttls.enable", "true")
  val ignoreFolders = sys.env.get("IGNORE_FOLDERS")
    .map(s => s.split(","))
    .getOrElse(Array.empty[String])
    .toSet
  
  val session = Session.getDefaultInstance(sessionProps)
  val mailStore = session.getStore("imap")
  val port = Option(sys.env("IMAP_PORT")).flatMap(_.toIntOption).getOrElse(143)
  mailStore.connect(sys.env("IMAP_HOST"), port, sys.env("IMAP_USERNAME"), sys.env("IMAP_PASSWORD"))
  val root = mailStore.getDefaultFolder()
  val stack = Stack[Folder](root.list(): _*)
  val messages = ArrayBuffer[PreprocessedMessage]()
  val start = System.currentTimeMillis()
  while (!stack.isEmpty) {
    val folder = stack.pop()
    val folderName = folder.getFullName()
    if (!(ignoreFolders contains folderName)) {
      println(s"Folder: $folderName -> ${folder.getMessageCount()}")
      stack.addAll(folder.list())

      folder.open(Folder.READ_ONLY)

      val folderStart = System.currentTimeMillis()
      val transformedMessages = folder.getMessages().par.map(msg => {
        val headers = msg.getAllHeaders().asScala.toSeq.collect[Header] {
          case h: Header => h
        }.groupMap(_.getName())(_.getValue())
        val content = Try(msg.getContent()).getOrElse(null)
        val contentHash = hashContent(content)
        PreprocessedMessage(folderName, headers, contentHash, Option(msg.getSubject()), Option(msg.getSentDate()), Option(msg.getSentDate()))
      }).toArray

      val durationMillis = System.currentTimeMillis() - folderStart
      val durationPerMailMillis = if (transformedMessages.size > 0) durationMillis.toDouble / transformedMessages.size else 0
      println(s"  Completed: ${durationMillis}ms (${durationPerMailMillis}ms per message)")

      messages.addAll(transformedMessages)

      folder.close(false)
    }
  }
  val totalDurationMillis = System.currentTimeMillis() - start
  val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

  def groupKey(message: PreprocessedMessage): String = {
    val hash = message.contentHash
    val date = message.headers.getHeadOrElse("Date", "")
    val sentAt = message.sentAt.map(d => dateFormat.format(d)).getOrElse("")
    val receivedAt = message.receivedAt.map(d => dateFormat.format(d)).getOrElse("")
    s"$hash-$date-$sentAt-$receivedAt"
  }

  val groupedByContentHash = messages.groupBy(groupKey)

  println(s"Messages total: ${messages.length}, ${totalDurationMillis}ms (${totalDurationMillis.toDouble / messages.length}ms per message)")

  for ((contentHash, group) <- groupedByContentHash if group.size > 1) {
    println(s"$contentHash:")
    for (message <- group) {
      println(s"    ${message.subject.getOrElse("")} (folder: ${message.folderName})")
    }
  }
}

extension [K, V] (m: Map[K, Seq[V]]) {
  def getHeadOrElse(key: K, default: V): V = m.get(key).flatMap(_.headOption).getOrElse(default)
}
