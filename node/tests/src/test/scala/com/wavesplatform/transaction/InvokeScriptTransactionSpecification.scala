package com.wavesplatform.transaction

import com.google.protobuf.ByteString
import com.wavesplatform.account._
import com.wavesplatform.api.http.requests.{InvokeScriptRequest, SignedInvokeScriptRequest}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base64, _}
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.compiler.Terms.{ARR, CONST_LONG, CaseObj}
import com.wavesplatform.lang.v1.compiler.Types.CASETYPEREF
import com.wavesplatform.lang.v1.{ContractLimits, FunctionHeader, Serde}
import com.wavesplatform.protobuf.transaction._
import com.wavesplatform.protobuf.{Amount, transaction}
import com.wavesplatform.serialization.Deser
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.NonPositiveAmount
import com.wavesplatform.transaction.smart.InvokeScriptTransaction.Payment
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, Verifier}
import com.wavesplatform.{TransactionGen, crypto}
import org.scalatest._
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}
import play.api.libs.json.{JsObject, Json}

class InvokeScriptTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  private val publicKey = PublicKey(ByteStr.decodeBase58("73pu8pHFNpj9tmWuYjqnZ962tXzJvLGX86dxjZxGYhoK").get)
  private val address = publicKey.toAddress

  property("InvokeScriptTransaction serialization roundtrip") {
    forAll(invokeScriptGen(paymentListGen)) { transaction: InvokeScriptTransaction =>
      val bytes = transaction.bytes()
      val deser = InvokeScriptTransaction.parseBytes(bytes).get
      deser.sender shouldEqual transaction.sender
      deser.dAppAddressOrAlias shouldEqual transaction.dAppAddressOrAlias
      deser.funcCallOpt shouldEqual transaction.funcCallOpt
      deser.payments shouldEqual transaction.payments
      deser.fee shouldEqual transaction.fee
      deser.timestamp shouldEqual transaction.timestamp
      deser.proofs shouldEqual transaction.proofs
      bytes shouldEqual deser.bytes()
      Verifier.verifyAsEllipticCurveSignature(transaction) shouldBe 'right
      Verifier.verifyAsEllipticCurveSignature(deser) shouldBe 'right // !!!!!!!!!!!!!!!
    }
  }

  property("protobuf roundtrip") {
    forAll(invokeScriptGen(paymentListGen), accountGen) { (tx, caller) =>
      val unsigned = transaction.PBTransaction(
        tx.chainId,
        ByteString.copyFrom(caller.publicKey.arr),
        Some(Amount.of(PBAmounts.toPBAssetId(tx.feeAssetId), tx.fee)),
        tx.timestamp,
        tx.version,
        transaction.PBTransaction.Data.InvokeScript(
          InvokeScriptTransactionData(
            Some(PBRecipients.create(tx.dAppAddressOrAlias)),
            ByteString.copyFrom(Deser.serializeOption(tx.funcCallOpt)(Serde.serialize(_))),
            tx.payments.map(p => Amount.of(PBAmounts.toPBAssetId(p.assetId), p.amount))
          )
        )
      )
      val proof        = crypto.sign(caller.privateKey, PBTransactions.vanilla(PBSignedTransaction(Some(unsigned))).explicitGet().bodyBytes())
      val signed       = PBSignedTransaction(Some(unsigned), Seq(ByteString.copyFrom(proof.arr)))
      val convTx       = PBTransactions.vanilla(signed).explicitGet()
      val unsafeConvTx = PBTransactions.vanillaUnsafe(signed)
      val modTx        = tx.copy(sender = caller.publicKey, proofs = Proofs(List(proof)))
      convTx.json() shouldBe modTx.json()
      unsafeConvTx.json() shouldBe modTx.json()
      crypto.verify(modTx.proofs.toSignature, modTx.bodyBytes(), modTx.sender) shouldBe true

      val convToPbTx = PBTransactions.protobuf(modTx)
      convToPbTx shouldBe signed
    }
  }

  property("decode pre-encoded bytes") {
    val bytes = Base64.decode(
      "ABABVFnfcU6tj7ELaOMRU60BmUEXZSyzyWDG4yxX597CilhGAVRewPqA+Z1914TfBt/z+m12Y6x3AVxAShIBCQEAAAADZm9vAAAAAQEAAAAFYWxpY2UAAQApAAAAAAAAAAcBWd9xTq2PsQto4xFTrQGZQRdlLLPJYMbjLFfn3sKKWEYAAAAAAAGGoAAAAAFjgvl7hQEAAQBAL4aaBFut6sRjmJqyUMSsW344/xjKn74k0tXmtbAMnZhCIysagYHWE578HZUBuKPxN/3v8OxBmN3lSChpsYrsCg=="
    )
    val json = Json.parse(s"""{
                         "type": 16,
                         "id": "9dDhnisRgSviJhLDUK2AYHpB8ycd6ammtkRRswAmMn8y",
                         "sender": "$address",
                         "senderPublicKey": "$publicKey",
                         "fee": 100000,
                         "feeAssetId": null,
                         "timestamp": 1526910778245,
                         "proofs": ["x7T161SxvUxpubEAKv4UL5ucB5pquAhTryZ8Qrd347TPuQ4yqqpVMQ2B5FpeFXGnpyLvb7wGeoNsyyjh5R61u7F"],
                         "version": 1,
                         "dApp" : "$address",
                         "call": {
                            "function" : "foo",
                             "args" : [
                             { "type" : "binary",
                               "value" : "base64:YWxpY2U="
                             }
                            ]
                          },
                         "payment" : [{
                            "amount" : 7,
                            "assetId" : "$publicKey"
                            }]
                        }
    """)

    val tx = InvokeScriptTransaction.serializer.parseBytes(bytes).get
    tx.json() shouldBe json
    ByteStr(tx.bytes()) shouldBe ByteStr(bytes)
  }

  property("JSON format validation for InvokeScriptTransaction") {
    val js = Json.parse(s"""{
                         "type": 16,
                         "id": "J1LqcE16AYfL2LfPZVse9gtWHAV6ZsseJzT6dMhGD9ek",
                         "sender": "$address",
                         "senderPublicKey": "$publicKey",
                         "fee": 100000,
                         "feeAssetId": null,
                         "timestamp": 1526910778245,
                         "proofs": ["x7T161SxvUxpubEAKv4UL5ucB5pquAhTryZ8Qrd347TPuQ4yqqpVMQ2B5FpeFXGnpyLvb7wGeoNsyyjh5R61u7F"],
                         "version": 1,
                         "dApp" : "3N2VhktvJsrW4PwMiFte3YsSeRgtideThTs",
                         "call": {
                            "function" : "foo",
                             "args" : [
                             { "type" : "binary",
                               "value" : "base64:YWxpY2U="
                             }
                            ]
                          },
                         "payment" : [{
                            "amount" : 7,
                            "assetId" : "$publicKey"
                            }]
                        }
    """)

    val tx = InvokeScriptTransaction
      .selfSigned(
        1.toByte,
        KeyPair("test3".getBytes("UTF-8")),
        KeyPair("test4".getBytes("UTF-8")).toAddress,
        Some(
          Terms.FUNCTION_CALL(
            FunctionHeader.User("foo"),
            List(Terms.CONST_BYTESTR(ByteStr(Base64.tryDecode("YWxpY2U=").get)).explicitGet())
          )
        ),
        Seq(InvokeScriptTransaction.Payment(7, IssuedAsset(publicKey))),
        100000,
        Waves,
        1526910778245L
      )
      .right
      .get

    (tx.json() - "proofs") shouldEqual (js.asInstanceOf[JsObject] - "proofs")

    TransactionFactory.fromSignedRequest(js) shouldBe Right(tx)
  }

  property("JSON format validation for InvokeScriptTransaction without FUNCTION_CALL") {
    val js = Json.parse(s"""{
                         "type": 16,
                         "id": "etdrhfKHguSAuqgAipW2vb2VmtHe4PNenoRETRqxjir",
                         "sender": "$address",
                         "senderPublicKey": "$publicKey",
                         "fee": 100000,
                         "feeAssetId": null,
                         "timestamp": 1526910778245,
                         "proofs": ["3frswEnyFZjTzBQ5pdNEJbPzvLp7Voz8sqZT3n7xsuVDdYGcasXgFNzb8HCrpNXYoDWLsHqrUSqcQfQJ8CRWjp4U"],
                         "version": 1,
                         "dApp" : "3N2VhktvJsrW4PwMiFte3YsSeRgtideThTs",
                         "payment" : [{
                            "amount" : 7,
                            "assetId" : "$publicKey"
                            }]
                        }
    """)

    val tx = InvokeScriptTransaction
      .selfSigned(
        1.toByte,
        KeyPair("test3".getBytes("UTF-8")),
        KeyPair("test4".getBytes("UTF-8")).toAddress,
        None,
        Seq(InvokeScriptTransaction.Payment(7, IssuedAsset(publicKey))),
        100000,
        Waves,
        1526910778245L
      )
      .right
      .get

    (tx.json() - "proofs") shouldEqual (js.asInstanceOf[JsObject] - "proofs")

    TransactionFactory.fromSignedRequest(js) shouldBe Right(tx)
  }

  property("Signed InvokeScriptTransactionRequest parser") {
    val req = SignedInvokeScriptRequest(
      Some(1.toByte),
      senderPublicKey = publicKey.toString,
      fee = 1,
      feeAssetId = None,
      call = Some(
        InvokeScriptRequest.FunctionCallPart(
          "bar",
          List(Terms.CONST_BYTESTR(ByteStr.decodeBase64("YWxpY2U=").get).explicitGet())
        )
      ),
      payment = Some(Seq(Payment(1, Waves))),
      dApp = address.toString,
      timestamp = 11,
      proofs = Proofs(List("CC1jQ4qkuVfMvB2Kpg2Go6QKXJxUFC8UUswUxBsxwisrR8N5s3Yc8zA6dhjTwfWKfdouSTAnRXCxTXb3T6pJq3T").map(s => ByteStr.decodeBase58(s).get))
    )
    req.toTx shouldBe 'right
  }

  property(s"can't have more than ${ContractLimits.MaxInvokeScriptArgs} args") {
    import com.wavesplatform.common.state.diffs.ProduceError._
    InvokeScriptTransaction.create(
      1.toByte,
      publicKey,
      address,
      Some(Terms.FUNCTION_CALL(FunctionHeader.User("foo"), Range(0, 23).map(_ => Terms.CONST_LONG(0)).toList)),
      Seq(),
      1,
      Waves,
      1,
      Proofs.empty
    ) should produce("more than 22 arguments")
  }

  property(s"can call a func with ARR") {
    InvokeScriptTransaction.create(
      1.toByte,
      publicKey,
      address,
      Some(
        Terms.FUNCTION_CALL(
          FunctionHeader.User("foo"),
          List(ARR(IndexedSeq(CONST_LONG(1L), CONST_LONG(2L)), false).explicitGet())
        )
      ),
      Seq(),
      1,
      Waves,
      1,
      Proofs.empty
    ) shouldBe 'right
  }

  property(s"can't call a func with non native(simple) args - CaseObj") {
    import com.wavesplatform.common.state.diffs.ProduceError._
    InvokeScriptTransaction.create(
      1.toByte,
      publicKey,
      address,
      Some(
        Terms.FUNCTION_CALL(
          FunctionHeader.User("foo"),
          List(CaseObj(CASETYPEREF("SHA256", List.empty), Map("tmpKey" -> CONST_LONG(42))))
        )
      ),
      Seq(),
      1,
      Waves,
      1,
      Proofs.empty
    ) should produce("is unsupported")
  }

  property("can't be more 5kb") {
    val largeString = "abcde" * 1024
    import com.wavesplatform.common.state.diffs.ProduceError._
    InvokeScriptTransaction.create(
      1.toByte,
      publicKey,
      address,
      Some(Terms.FUNCTION_CALL(FunctionHeader.User("foo"), List(Terms.CONST_STRING(largeString).explicitGet()))),
      Seq(),
      1,
      Waves,
      1,
      Proofs.empty
    ) should produce("TooBigArray")
  }

  property("can't have zero amount") {
    val req = SignedInvokeScriptRequest(
      Some(1.toByte),
      senderPublicKey = publicKey.toString,
      fee = 1,
      feeAssetId = None,
      call = Some(
        InvokeScriptRequest.FunctionCallPart(
          "bar",
          List(Terms.CONST_BYTESTR(ByteStr.decodeBase64("YWxpY2U=").get).explicitGet())
        )
      ),
      payment = Some(Seq(Payment(0, Waves))),
      dApp = address.toString,
      timestamp = 11,
      proofs = Proofs(List("CC1jQ4qkuVfMvB2Kpg2Go6QKXJxUFC8UUswUxBsxwisrR8N5s3Yc8zA6dhjTwfWKfdouSTAnRXCxTXb3T6pJq3T").map(s => ByteStr.decodeBase58(s).get))
    )
    req.toTx shouldBe Left(NonPositiveAmount(0, "Waves"))
  }

  property("can't have negative amount") {
    val req = SignedInvokeScriptRequest(
      Some(1.toByte),
      senderPublicKey = publicKey.toString,
      fee = 1,
      feeAssetId = None,
      call = Some(
        InvokeScriptRequest.FunctionCallPart(
          "bar",
          List(Terms.CONST_BYTESTR(ByteStr.decodeBase64("YWxpY2U=").get).explicitGet())
        )
      ),
      payment = Some(Seq(Payment(-1, Waves))),
      dApp = address.toString,
      timestamp = 11,
      proofs = Proofs(List("CC1jQ4qkuVfMvB2Kpg2Go6QKXJxUFC8UUswUxBsxwisrR8N5s3Yc8zA6dhjTwfWKfdouSTAnRXCxTXb3T6pJq3T").map(s => ByteStr.decodeBase58(s).get))
    )
    req.toTx shouldBe Left(NonPositiveAmount(-1, "Waves"))
  }
}
