package id.walt.webwallet.backend.wallet

import com.beust.klaxon.Klaxon
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.util.URLUtils
import com.nimbusds.openid.connect.sdk.OIDCScopeValue
import com.nimbusds.openid.connect.sdk.SubjectType
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata
import id.walt.crypto.KeyAlgorithm
import id.walt.custodian.Custodian
import id.walt.model.DidMethod
import id.walt.model.oidc.IssuanceInitiationRequest
import id.walt.model.oidc.klaxon
import id.walt.rest.core.DidController
import id.walt.rest.custodian.CustodianController
import id.walt.services.context.ContextManager
import id.walt.services.did.DidService
import id.walt.services.ecosystems.essif.EssifClient
import id.walt.services.ecosystems.essif.didebsi.DidEbsiService
import id.walt.services.key.KeyService
import id.walt.services.oidc.OIDC4VPService
import id.walt.signatory.ProofConfig
import id.walt.signatory.Signatory
import id.walt.signatory.dataproviders.MergingDataProvider
import id.walt.vclib.credentials.gaiax.n.LegalPerson
import id.walt.vclib.model.toCredential
import id.walt.webwallet.backend.auth.JWTService
import id.walt.webwallet.backend.auth.UserRole
import id.walt.webwallet.backend.config.WalletConfig
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.http.HttpCode
import io.javalin.plugin.openapi.dsl.document
import io.javalin.plugin.openapi.dsl.documented
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

data class GaiaXCredentialRequest(val credentialData: Map<String, Any>? = null)

object WalletController {
    val routes
        get() = path("") {
            path("wallet") {
                path("did") {
                    // list DIDs
                    path("list") {
                        get(
                            documented(document().operation {
                                it.summary("List DIDs").operationId("listDids").addTagsItem("DIDs")
                            }
                                .jsonArray<String>("200"),
                                WalletController::listDids
                            ), UserRole.AUTHORIZED
                        )
                    }
                    // load DID
                    get("{id}", documented(document().operation {
                        it.summary("Load DID").operationId("load").addTagsItem("DIDs")
                    }
                        .json<String>("200"), DidController::load), UserRole.AUTHORIZED)
                    // create new DID
                    path("create") {
                        post(
                            documented(document().operation {
                                it.summary("Create new DID")
                                    .description("Creates and registers a DID. Currently the DID methods: key, web and ebsi are supported. For EBSI: a  bearer token is required.")
                                    .operationId("createDid").addTagsItem("DIDs")
                            }
                                .body<DidCreationRequest>()
                                .result<String>("200"),
                                WalletController::createDid
                            ), UserRole.AUTHORIZED
                        )
                    }
                }
                path("credentials") {
                    get(
                        "list",
                        documented(CustodianController.listCredentialIdsDocs(), CustodianController::listCredentials),
                        UserRole.AUTHORIZED
                    )
                    delete(
                        "delete/{alias}",
                        documented(CustodianController.deleteCredentialDocs(), CustodianController::deleteCredential),
                        UserRole.AUTHORIZED
                    )
                    put(
                        "{alias}",
                        documented(CustodianController.storeCredentialsDocs(), CustodianController::storeCredential),
                        UserRole.AUTHORIZED
                    )
                }
                path("keys") {
                    get(
                        "list",
                        documented(CustodianController.listKeysDocs(), CustodianController::listKeys),
                        UserRole.AUTHORIZED
                    )
                    post(
                        "import",
                        documented(CustodianController.importKeysDocs(), CustodianController::importKey),
                        UserRole.AUTHORIZED
                    )
                    delete(
                        "delete/{id}",
                        documented(CustodianController.deleteKeysDocs(), CustodianController::deleteKey),
                        UserRole.AUTHORIZED
                    )
                    post(
                        "export",
                        documented(CustodianController.exportKeysDocs(), CustodianController::exportKey),
                        UserRole.AUTHORIZED
                    )
                }
                path("presentation") {
                    // called by wallet UI
                    get("create", documented(
                        document().operation {
                            it.summary("Create presentation requested by verifer via WalletConnect")
                                .operationId("createPresentation")
                                .addTagsItem("Presentation")
                        }
                            .queryParam<String>("type")
                            .json<CredentialPresentationSessionInfo>("200"),
                        WalletController::createPresentationSession
                    ), UserRole.AUTHORIZED)
                    // called by wallet UI
                    get("continue", documented(
                        document().operation {
                            it.summary("Continue presentation requested by verifer")
                                .operationId("continuePresentation")
                                .addTagsItem("Presentation")
                        }
                            .queryParam<String>("sessionId")
                            .queryParam<String>("did")
                            .json<CredentialPresentationSessionInfo>("200"),
                        WalletController::continuePresentation
                    ), UserRole.AUTHORIZED)
                    // called by wallet UI
                    post("fulfill", documented(
                        document().operation {
                            it.summary("Fullfil credentials presentation with selected credentials")
                                .operationId("fulfillPresentation")
                                .addTagsItem("Presentation")
                        }
                            .queryParam<String>("sessionId")
                            .body<List<PresentableCredential>>()
                            .json<PresentationResponse>("200"),
                        WalletController::fulfillPresentation
                    ), UserRole.AUTHORIZED)
                }
                path("issuer") {
                    get(
                        "list", documented(
                            document().operation {
                                it.summary("List known credential issuers").addTagsItem("Issuers")
                                    .operationId("listIssuers")
                            },
                            WalletController::listIssuers
                        ),
                        UserRole.UNAUTHORIZED
                    )
                    get("metadata", documented(
                        document().operation {
                            it.summary("get issuer meta data").addTagsItem("Issuers").operationId("issuerMeta")
                        }
                            .queryParam<String>("issuerId"),
                        WalletController::issuerMeta),
                        UserRole.UNAUTHORIZED)
                }
                path("issuance") {
                    post("start", documented(
                        document().operation {
                            it.summary("Initialize credential issuance from selected issuer").addTagsItem("Issuance")
                                .operationId("initIssuance")
                        }
                            .body<CredentialIssuanceRequest>()
                            .result<String>("200"),
                        WalletController::startIssuance
                    ), UserRole.AUTHORIZED)
                    post("startForPresentation", documented(
                        document().operation {
                            it.summary("Initialize credential issuance from selected issuer").addTagsItem("Issuance")
                                .operationId("initIssuance")
                        }
                            .body<CredentialIssuance4PresentationRequest>()
                            .result<String>("200"),
                        WalletController::startIssuanceForPresentation
                    ), UserRole.AUTHORIZED)
                    // called by wallet UI
                    get("info", documented(
                        document().operation {
                            it.summary("Get issuance session info, including issued credentials").addTagsItem("Issuance")
                                .operationId("issuanceSessionInfo")
                        }
                            .queryParam<String>("sessionId")
                            .json<CredentialIssuanceSession>("200"),
                        WalletController::getIssuanceSessionInfo
                    ), UserRole.AUTHORIZED)
                    post("startIssuerInitiatedIssuance", documented(
                        document().operation {
                            it.summary("Start an issuer-initiated issuance session from an OIDC URL, that could be scanned from a QR code (cross device)")
                                .addTagsItem("Issuance")
                                .operationId("continueIssuerInitiatedIssuance")
                        }
                            .body<CrossDeviceIssuanceInitiationRequest>()
                            .result<String>("200"),
                        WalletController::startIssuerInitiatedIssuance
                    ), UserRole.AUTHORIZED)
                    get("continueIssuerInitiatedIssuance", documented(
                        document().operation {
                            it.summary("Continue an issuer-initiated issuance session, after user accepted the issuance request")
                                .addTagsItem("Issuance")
                                .operationId("continueIssuerInitiatedIssuance")
                        }
                            .queryParam<String>("sessionId")
                            .queryParam<String>("did")
                            .queryParam<String>("userPin")
                            .result<String>("200"),
                        WalletController::continueIssuerInitiatedIssuance
                    ), UserRole.AUTHORIZED)
                }
                path("onboard") {
                    path("gaiax/{did}") {
                        post(
                            documented(document().operation {
                                it.summary("Onboard legal person")
                                    .description("Creates a gaia-x compliant credential from the given self-description.")
                                    .operationId("onboardGaiaX").addTagsItem("DIDs")
                            }
                                .body<String>()
                                .result<String>("200"),
                                WalletController::onboardGaiaX
                            ), UserRole.AUTHORIZED
                        )
                    }
                }
            }
            path("siop") {
                get(".well-known/openid-configuration", documented(
                    document().operation {
                        it.summary("get OIDC provider meta data")
                            .addTagsItem("SIOP")
                            .operationId("oidcProviderMeta")
                    }
                        .json<OIDCProviderMetadata>("200"),
                    WalletController::oidcProviderMeta
                ))
                // called from EXTERNAL verifier
                get("initiatePresentation", documented(
                    document().operation {
                        it.summary("Parse siop request from URL query parameters")
                            .operationId("initPresentation")
                            .addTagsItem("SIOP").addTagsItem("OIDC4VP")
                    }
                        .queryParam<String>("response_type")
                        .queryParam<String>("client_id")
                        .queryParam<String>("redirect_uri")
                        .queryParam<String>("scope")
                        .queryParam<String>("state")
                        .queryParam<String>("nonce")
                        .queryParam<String>("registration")
                        .queryParam<Long>("exp")
                        .queryParam<Long>("iat")
                        .queryParam<String>("claims")
                        .result<String>("302"),
                    WalletController::initCredentialPresentation
                ), UserRole.UNAUTHORIZED)
                // called from EXTERNAL issuer / user-agent
                get("initiateIssuance", documented(
                    document().operation {
                        it.summary("Issuance initiation (OIDC4VCI) endpoint").addTagsItem("SIOP").addTagsItem("OIDC4VCI")
                            .operationId("initiateIssuance")
                    }
                        .queryParam<String>("issuer")
                        .queryParam<String>("credential_type", isRepeatable = true)
                        .queryParam<String>("pre-authorized_code")
                        .queryParam<Boolean>("user_pin_required")
                        .queryParam<String>("op_state")
                        .result<String>("200"),
                    WalletController::initiateIssuance
                ), UserRole.UNAUTHORIZED)
                // called from EXTERNAL issuer / user-agent
                get("finalizeIssuance", documented(
                    document().operation {
                        it.summary("Finalize credential issuance").addTagsItem("SIOP").addTagsItem("OIDC4VCI")
                            .operationId("finalizeIssuance")
                    }
                        .queryParam<String>("code")
                        .queryParam<String>("state")
                        .result<String>("302"),
                    WalletController::finalizeIssuance
                ), UserRole.UNAUTHORIZED)
            }
        }

    fun oidcProviderMeta(ctx: Context) {
        ctx.json(
            OIDCProviderMetadata(
                Issuer(WalletConfig.config.walletUiUrl),
                listOf(SubjectType.PAIRWISE),
                URI.create("")
            ).apply {
                authorizationEndpointURI = URI("${WalletConfig.config.walletApiUrl}/siop/initiatePresentation")
                setCustomParameter("initiate_issuance_endpoint", "${WalletConfig.config.walletApiUrl}/siop/initiateIssuance")
                scopes = Scope(OIDCScopeValue.OPENID)
                responseTypes = listOf(ResponseType.IDTOKEN, ResponseType("vp_token"))

            }.toJSONObject()
        )
    }

    fun listDids(ctx: Context) {
        ctx.json(DidService.listDids())
    }

    fun loadDid(ctx: Context) {
        val id = ctx.pathParam("id")
        ctx.json(DidService.load(id))
    }

    fun createDid(ctx: Context) {
        val req = ctx.bodyAsClass<DidCreationRequest>()

        val keyId = req.keyId?.let { KeyService.getService().load(it).keyId.id }

        when (req.method) {
            DidMethod.ebsi -> {
                if (req.didEbsiBearerToken.isNullOrEmpty()) {
                    ctx.status(HttpCode.BAD_REQUEST)
                        .result("ebsiBearerToken form parameter is required for EBSI DID registration.")
                    return
                }

                val did =
                    DidService.create(req.method, keyId ?: KeyService.getService().generate(KeyAlgorithm.ECDSA_Secp256k1).id)
                EssifClient.onboard(did, req.didEbsiBearerToken)
                EssifClient.authApi(did)
                DidEbsiService.getService().registerDid(did, did)
                ctx.result(did)
            }

            DidMethod.web -> {
                val didRegistryAuthority = URI.create(WalletConfig.config.walletApiUrl).authority
                val didDomain = req.didWebDomain.orEmpty().ifEmpty { didRegistryAuthority }


                val didWebKeyId = keyId ?: KeyService.getService().generate(KeyAlgorithm.EdDSA_Ed25519).id
                val didStr = DidService.create(
                    req.method,
                    didWebKeyId,
                    DidService.DidWebOptions(
                        domain = didDomain,
                        path = when (didDomain) {
                            didRegistryAuthority -> "api/did-registry/$didWebKeyId"
                            else -> req.didWebPath
                        }
                    )
                )
                val didDoc = DidService.load(didStr)
                // !! Implicit USER CONTEXT is LOST after this statement !!
                ContextManager.runWith(DidWebRegistryController.didRegistryContext) {
                    DidService.storeDid(didStr, didDoc.encodePretty())
                }

                ctx.result(didStr)
            }

            DidMethod.key -> {

                ctx.result(
                    DidService.create(
                        req.method,
                        keyId ?: KeyService.getService().generate(KeyAlgorithm.EdDSA_Ed25519).id
                    )
                )
            }

            else -> throw BadRequestResponse("DID method ${req.method} not yet supported")
        }
    }

    fun initCredentialPresentation(ctx: Context) {
        val req = OIDC4VPService.parseOIDC4VPRequestUriFromHttpCtx(ctx)
        val session = CredentialPresentationManager.initCredentialPresentation(req)
        ctx.status(HttpCode.FOUND)
            .header("Location", "${WalletConfig.config.walletUiUrl}/CredentialRequest/?sessionId=${session.id}")
    }

    fun initiateIssuance(ctx: Context) {
        val issuanceInitiationReq = IssuanceInitiationRequest.fromQueryParams(ctx.queryParamMap())
        val sessionId = CredentialIssuanceManager.startIssuerInitiatedIssuance(issuanceInitiationReq)
        ctx.status(HttpCode.FOUND)
            .header("Location", "${WalletConfig.config.walletUiUrl}/InitiateIssuance/?sessionId=${sessionId}")
    }

    fun createPresentationSession(ctx: Context) {
        val type = ctx.queryParam("type") ?: throw BadRequestResponse("Missing type parameter")
        val session = CredentialPresentationManager.createCredentialPresentationSessionFor(type)
        ctx.json(session.sessionInfo)
    }

    fun continuePresentation(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId") ?: throw BadRequestResponse("sessionId not specified")
        val did = ctx.queryParam("did") ?: throw BadRequestResponse("did not specified")
        ctx.contentType(ContentType.APPLICATION_JSON).result(
            klaxon.toJsonString(
                CredentialPresentationManager.continueCredentialPresentationFor(
                    sessionId,
                    did
                ).sessionInfo
            )
        )
    }

    fun fulfillPresentation(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId") ?: throw BadRequestResponse("sessionId not specified")
        val selectedCredentials = ctx.body().let { klaxon.parseArray<PresentableCredential>(it) }
            ?: throw BadRequestResponse("No selected credentials given")

        ctx.json(
            CredentialPresentationManager.fulfillPresentation(sessionId, selectedCredentials)
                .let { PresentationResponse.fromSiopResponse(it) })
    }

    fun listIssuers(ctx: Context) {
        ctx.json(WalletConfig.config.issuers.values)
    }

    fun issuerMeta(ctx: Context) {
        val metadata = ctx.queryParam("issuerId")?.let {
            CredentialIssuanceManager.getIssuerWithMetadata(it)
        }?.oidc_provider_metadata

        if (metadata != null)
            ctx.json(metadata.toJSONObject())
        else
            ctx.status(HttpCode.NOT_FOUND)
    }

    fun startIssuance(ctx: Context) {
        val issuance = ctx.bodyAsClass<CredentialIssuanceRequest>()
        val location = CredentialIssuanceManager.startIssuance(issuance, JWTService.getUserInfo(ctx)!!)
        ctx.result(location.toString())
    }

    fun startIssuanceForPresentation(ctx: Context) {
        val issuance = ctx.bodyAsClass<CredentialIssuance4PresentationRequest>()
        val location = CredentialIssuanceManager.startIssuanceForPresentation(issuance, JWTService.getUserInfo(ctx)!!)
        ctx.result(location.toString())
    }

    fun startIssuerInitiatedIssuance(ctx: Context) {
        val req = ctx.bodyAsClass<CrossDeviceIssuanceInitiationRequest>()
        val issuanceInitiationReq =
            IssuanceInitiationRequest.fromQueryParams(URLUtils.parseParameters(URI.create(req.oidcUri).query))
        val sessionId = CredentialIssuanceManager.startIssuerInitiatedIssuance(issuanceInitiationReq)
        ctx.result(sessionId)
    }

    fun continueIssuerInitiatedIssuance(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId") ?: throw BadRequestResponse("Missing sessionId parameter")
        val did = ctx.queryParam("did") ?: throw BadRequestResponse("Missing did parameter")
        val userPin = ctx.queryParam("userPin")
        try {
            val session = CredentialIssuanceManager.continueIssuerInitiatedIssuance(
                sessionId,
                did,
                JWTService.getUserInfo(ctx)!!,
                userPin
            )
            val location =
                if (!session.isPreAuthorized) { // not pre-authorized, execute PAR request and provide user-agent address for authorization step
                    CredentialIssuanceManager.executeAuthorizationStep(session).toString()
                } else { // pre-authorized issuance session, return UI address to success or error page
                    if (session.credentials != null) {
                        "/ReceiveCredential/?sessionId=${session.id}"
                    } else {
                        "/IssuanceError/"
                    }
                }
            ctx.result(location)
        } catch (exc: Exception) {
            ctx.result("/IssuanceError/?reason=${URLEncoder.encode(exc.message, StandardCharsets.UTF_8)}")
        }
    }

    fun finalizeIssuance(ctx: Context) {
        val state = ctx.queryParam("state")
        val code = ctx.queryParam("code")
        if (state.isNullOrEmpty() || code.isNullOrEmpty()) {
            ctx.status(HttpCode.BAD_REQUEST).result("No state or authorization code given")
            return
        }
        val issuance = CredentialIssuanceManager.finalizeIssuance(state, code)
        if (issuance.credentials != null) {
            ctx.status(HttpCode.FOUND)
                .header("Location", "${WalletConfig.config.walletUiUrl}/ReceiveCredential/?sessionId=${issuance.id}")
        } else {
            ctx.status(HttpCode.FOUND).header("Location", "${WalletConfig.config.walletUiUrl}/IssuanceError/")
        }
    }

    fun getIssuanceSessionInfo(ctx: Context) {
        val sessionId = ctx.queryParam("sessionId")
        val issuanceSession = sessionId?.let { CredentialIssuanceManager.getSession(it) }
        if (issuanceSession == null) {
            ctx.status(HttpCode.BAD_REQUEST).result("Invalid or expired session id given")
            return
        }
        ctx.contentType(ContentType.JSON).result(klaxon.toJsonString(issuanceSession))
    }

    fun onboardGaiaX(ctx: Context) {
        val credential = Klaxon().parse<LegalPerson>(ctx.body())
        val did = ctx.pathParam("did")
        val compliance = issueSelfSignedCredential(
            "LegalPerson",
            did,
            did,
            credential?.copy(proof = null)?.toMap()
        ).run {
            this.toCredential().let {
                Custodian.getService().storeCredential(it.id ?: UUID.randomUUID().toString(), it)
            }
            // TODO: this is just for demo purpose, generate credential from compliance service
            issueSelfSignedCredential(
                "ParticipantCredential",
                did,
                did,
            ).toCredential().run {
                Custodian.getService().storeCredential(this.id ?: UUID.randomUUID().toString(), this)
                this
            }.encode()
//            GaiaxService.getService().generateGaiaxComplianceCredential(this)
        }
        ctx.result(compliance)
    }

    private fun issueSelfSignedCredential(
        template: String,
        did: String,
        verificationMethod: String,
        data: Map<String, Any>? = null
    ): String {
        return Signatory.getService().issue(
            template, ProofConfig(
                subjectDid = did,
                issuerDid = did,
                issueDate = LocalDateTime.now().toInstant(ZoneOffset.UTC),
                issuerVerificationMethod = verificationMethod,
                proofPurpose = "assertionMethod"
            ), dataProvider = data?.let { MergingDataProvider(data) }
        )
    }

}
