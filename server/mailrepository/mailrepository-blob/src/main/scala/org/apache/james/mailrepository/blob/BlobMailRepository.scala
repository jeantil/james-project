/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailrepository.blob

import com.google.common.collect.ImmutableMap
import jakarta.mail.{MessagingException, Session}
import jakarta.mail.internet.MimeMessage
import org.apache.commons.lang3.StringUtils
import org.apache.james.blob.api.BlobStore.StoragePolicy
import org.apache.james.blob.api.BlobStore.StoragePolicy.SIZE_BASED
import org.apache.james.blob.api._
import org.apache.james.blob.mail.MimeMessagePartsId
import org.apache.james.core.{MailAddress, MaybeSender}
import org.apache.james.mailrepository.api.{MailKey, MailRepository, MailRepositoryUrl}
import org.apache.james.server.core.{MailImpl, MimeMessageInputStream}
import org.apache.james.util.AuditTrail
import org.apache.mailet._
import play.api.libs.json.{Format, Json}
import reactor.core.publisher.{Flux, Mono}
import reactor.core.scala.publisher.SMono
import reactor.util.function.Tuples

import java.io.{ByteArrayInputStream, InputStream}
import java.util
import java.util.{Date, Properties}
import scala.jdk.CollectionConverters.IterableHasAsJava

private[blob] object serializers {
  implicit val headerFormat: Format[Header] = Json.format[Header]
  implicit val mailMetadataFormat: Format[MailMetadata] = Json.format[MailMetadata]
}

object BlobMailRepository {
  private[blob] object MailPartsId {
    private[blob] val METADATA_BLOB_TYPE = new BlobType("mailMetadata", SIZE_BASED)

    class Factory extends BlobPartsId.Factory[BlobMailRepository.MailPartsId] {
      override def generate(map: util.Map[BlobType, BlobId]): MailPartsId = {
        require(map.containsKey(METADATA_BLOB_TYPE), "Expecting 'mailMetadata' blobId to be specified")
        require(map.size == 1, "blobId other than 'mailMetadata' are not supported")
        new BlobMailRepository.MailPartsId(map.get(METADATA_BLOB_TYPE))
      }
    }
  }

  private[blob] case class MailPartsId private[blob](metadataBlobId: BlobId) extends BlobPartsId {
    override def asMap: ImmutableMap[BlobType, BlobId] = ImmutableMap.of(MailPartsId.METADATA_BLOB_TYPE, metadataBlobId)

    def toMailKey: MailKey = new MailKey(metadataBlobId.asString())
  }
}

class BlobMailRepository(val mailMetaDataBlobStore: BlobStore,
                         val mailMetadataBlobIdFactory: MailRepositoryBlobIdFactory,
                         val mimeMessageStore: Store[MimeMessage, MimeMessagePartsId],
                         val url: MailRepositoryUrl,
                        ) extends MailRepository {

  import BlobMailRepository._


  @throws[MessagingException]
  override def store(mc: Mail): MailKey = {
    val mailKey = MailKey.forMail(mc)
    // The logical blobId that the rest of the system will know
    val blobId = mailMetadataBlobIdFactory.of(mailKey.asString())
    // By nesting both blobIds under the MailKey we don't need to save one
    // before the other nor do we need one to know the blobId of the other
    // Also I think we can delete them both using the prefix thus ensuring
    // some kind of atomic deletion
    // The downside of this change is that this new encoding is not backward
    // compatible with the previous implementation ( breaking change )
    val mimeMessageBlobId = mailMetadataBlobIdFactory.of(mailKey.asString() + "/mimeMessage")
    val mailMetadataBlobId = mailMetadataBlobIdFactory.of(mailKey.asString() + "/mailMetadata")

    // FIXME - We could save both in parallel but let's take this one step at a time
    Mono.from(
        mailMetaDataBlobStore.save(
          mailMetaDataBlobStore.getDefaultBucketName,
          new MimeMessageInputStream(mc.getMessage),
          (in: InputStream) => SMono.just(Tuples.of(mimeMessageBlobId, in)),
          StoragePolicy.LOW_COST
        )
      ).flatMap(_ =>
        // FIXME - The MimeMessagePartsId are no longer necessary but we will get rid of them in the next step
        saveMailMetadata(mc, MimeMessagePartsId.builder().headerBlobId(mailMetadataBlobId).bodyBlobId(mimeMessageBlobId).build())
      )
      .doOnSuccess(_ => AuditTrail.entry
        .protocol("mailrepository")
        .action("store")
        .parameters(() => ImmutableMap.of("mailId", mc.getName,
          "mimeMessageId", Option(mc.getMessage)
            .flatMap(message => Option(message.getMessageID))
            .getOrElse(""),
          "sender", mc.getMaybeSender.asString,
          "recipients", StringUtils.join(mc.getRecipients)))
        .log(s"BlobMailRepository stored mail.${mc.getName}"))
      .map(_ => new MailKey(blobId.asString()))
      .block()
  }

  private def saveMailMetadata(mail: Mail, partsIds: MimeMessagePartsId): Mono[MailPartsId] = {
    import serializers._

    val mailMetadata = MailMetadata.of(mail, partsIds)
    val payload = Json.stringify(Json.toJson(mailMetadata))
    val blobId = partsIds.getHeaderBlobId

    SMono.fromPublisher(
      mailMetaDataBlobStore.save(
        mailMetaDataBlobStore.getDefaultBucketName,
        new ByteArrayInputStream(payload.getBytes),
        (data: InputStream) => SMono.just(Tuples.of(blobId, data)).asJava(),
        BlobStore.StoragePolicy.SIZE_BASED)
    ).map(it => MailPartsId(it)).asJava()
  }

  @throws[MessagingException]
  override def size: Long =
    listMailRepositoryBlobs
      .count()
      .block()

  @throws[MessagingException]
  override def list: util.Iterator[MailKey] = {
    listMailRepositoryBlobs
      .map[MailKey](blobId => new MailKey(blobId.asString))
      .toIterable
      .iterator
  }

  private def listMailRepositoryBlobs = {
    Flux.from(mailMetaDataBlobStore.listBlobs(mailMetaDataBlobStore.getDefaultBucketName))
      .filter(this.belongsToMailRepository)
      // we filter only on mime message keys to avoid duplicates
      .filter(id=>id.asString().endsWith("/mimeMessage"))
      .map(id => mailMetadataBlobIdFactory.parse(id.asString().stripSuffix("/mimeMessage")))
  }

  private def belongsToMailRepository(blobId: BlobId): Boolean =
    blobId.asString().startsWith(url.getPath.asString())

  @throws[MessagingException]
  override def retrieve(key: MailKey): Mail = {
    // FIXME - construction should be factorized
    readMailMetadata(mailMetadataBlobIdFactory.parse(key.asString() + "/mailMetadata"))
      .flatMap { value =>
        val mail = readMail(value)
        SMono.fromCallable(()=>
          mailMetaDataBlobStore.read(
            mailMetaDataBlobStore.getDefaultBucketName,
            mailMetadataBlobIdFactory.parse(key.asString() + "/mimeMessage")
          )
        ).using(
          in => SMono.just(new MimeMessage(Session.getInstance(new Properties()), in))
        )(
          _.close
        ).map { mimeMessage =>
          mail.setMessage(mimeMessage)
          mail
        }
      }
      .onErrorResume(e => SMono.empty)
      .blockOption()
      .orNull
  }

  private def readMailMetadata(blobId: BlobId): SMono[MailMetadata] = {
    import serializers._

    SMono.fromCallable(() => mailMetaDataBlobStore.read(mailMetaDataBlobStore.getDefaultBucketName, blobId))
      .using(
        source => SMono.just(Json.fromJson[MailMetadata](Json.parse(source)).get)
      )(in => in.close())
  }

  private def readMail(mailMetadata: MailMetadata): Mail = {
    val builder = MailImpl.builder
      .name(mailMetadata.name)
      .sender(mailMetadata.sender.map(MaybeSender.getMailSender).getOrElse(MaybeSender.nullSender))
      .addRecipients(mailMetadata.recipients.map(new MailAddress(_)).asJavaCollection)
      .remoteAddr(mailMetadata.remoteAddr)
      .remoteHost(mailMetadata.remoteHost)

    mailMetadata.state.foreach(builder.state)
    mailMetadata.errorMessage.foreach(builder.errorMessage)

    mailMetadata.lastUpdated.map(Date.from).foreach(builder.lastUpdated)

    mailMetadata.attributes.foreach { case (name, value) => builder.addAttribute(new Attribute(AttributeName.of(name), AttributeValue.fromJsonString(value))) }

    builder.addAllHeadersForRecipients(retrievePerRecipientHeaders(mailMetadata.perRecipientHeaders))

    builder.build
  }

  private def retrievePerRecipientHeaders(perRecipientHeaders: Map[String, Iterable[Header]]): PerRecipientHeaders = {
    val result = new PerRecipientHeaders()
    perRecipientHeaders.foreach { case (key, value) =>
      value.foreach(headers => {
        headers.values.foreach(header => {
          val builder = PerRecipientHeaders.Header.builder().name(headers.key).value(header)
          result.addHeaderForRecipient(builder, new MailAddress(key))
        })
      })
    }
    result
  }

  @throws[MessagingException]
  override def remove(key: MailKey): Unit = {
    remove(mailMetadataBlobIdFactory.parse(key.asString()))
      .onErrorResume(_ => SMono.empty)
      .block()
  }

  private def remove(blobId: BlobId): SMono[Unit] =
    for {
      _ <- SMono(mailMetaDataBlobStore.delete(mailMetaDataBlobStore.getDefaultBucketName, mailMetadataBlobIdFactory.parse(blobId.asString() + "/mailMetadata")))
      _ <- SMono(mailMetaDataBlobStore.delete(mailMetaDataBlobStore.getDefaultBucketName, mailMetadataBlobIdFactory.parse(blobId.asString() + "/mimeMessage")))
    } yield ()


  @throws[MessagingException]
  override def removeAll(): Unit = {
    Flux.from(mailMetaDataBlobStore.listBlobs(mailMetaDataBlobStore.getDefaultBucketName))
      .filter(this.belongsToMailRepository)
      .flatMap(blobId => mailMetaDataBlobStore.delete(mailMetaDataBlobStore.getDefaultBucketName,blobId))
      .blockLast()
  }
}