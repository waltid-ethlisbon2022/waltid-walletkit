package id.walt.onboarding.backend

import id.walt.crypto.toHexString
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.naming.Context
import javax.naming.directory.DirContext
import javax.naming.directory.InitialDirContext

object DomainOwnershipService {

    /**
     * Unique verification code for each domain. The code will change each day.
     */
    fun generateWaltIdDomainVerificationCode(domain: String): String =
        "ssi-onboarding-verification=" + MessageDigest.getInstance("SHA-1")
            .digest((LocalDateTime.now().format(DateTimeFormatter.ISO_DATE) + domain).toByteArray()).toHexString()
            .replace(" ", "")

    fun checkWaltIdDomainVerificationCode(domain: String): Boolean =
        checkDomainVerificationCode(domain, generateWaltIdDomainVerificationCode(domain))

    fun checkDomainVerificationCode(domain: String, code: String): Boolean {
        println("Checking domain: $domain (code: $code)")
        val env: Hashtable<String, Any> = Hashtable<String, Any>()
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
        env.put(Context.PROVIDER_URL, "dns:")

        val ctx: DirContext = InitialDirContext(env)
        val attributes = ctx.getAttributes(domain, arrayOf("TXT")).all
        while (attributes.hasMore()) {
            if (attributes.next().contains(code)) {
                return true
            }
        }
        return false
    }

}