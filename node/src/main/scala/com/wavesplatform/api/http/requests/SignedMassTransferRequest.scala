package com.wavesplatform.api.http.requests

import com.wavesplatform.account.PublicKey
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer.MassTransferTransaction.Transfer
import com.wavesplatform.transaction.transfer._
import play.api.libs.functional.syntax._
import play.api.libs.json._

object SignedMassTransferRequest {
  implicit val MassTransferRequestReads: Reads[SignedMassTransferRequest] = (
    (JsPath \ "version").readNullable[Byte] and
      (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "assetId").readNullable[String] and
      (JsPath \ "transfers").read[List[Transfer]] and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "attachment").readNullable[Attachment] and
      (JsPath \ "proofs").read[Proofs]
  )(SignedMassTransferRequest.apply _)
}

case class SignedMassTransferRequest(
    version: Option[Byte],
    senderPublicKey: String,
    assetId: Option[String],
    transfers: List[Transfer],
    fee: Long,
    timestamp: Long,
    attachment: Option[Attachment],
    proofs: Proofs
) {
  def toTx: Either[ValidationError, MassTransferTransaction] =
    for {
      _sender    <- PublicKey.fromBase58String(senderPublicKey)
      _assetId   <- parseBase58ToAsset(assetId.filter(_.length > 0), "invalid.assetId")
      _transfers <- MassTransferTransaction.parseTransfersList(transfers)
      t          <- MassTransferTransaction.create(version.getOrElse(1.toByte), _sender, _assetId, _transfers, fee, timestamp, attachment, proofs)
    } yield t
}
