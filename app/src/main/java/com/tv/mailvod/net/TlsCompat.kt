package com.tv.mailvod.net

import android.content.Context
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 老安卓 TLS 兼容层。
 *
 * 背景: Android 7.1.1(API 25) 及以下的系统信任库不含 ISRG Root X1/X2,
 *      导致 Let's Encrypt 证书的源 (如量子 lz-cdn*.com) TLS 握手失败,
 *      ExoPlayer/HttpURLConnection 层面表现为 Source error。
 *
 * 方案: 打包 ISRG Root X1/X2 进 assets, 构造 [CompositeTrustManager]
 *      (系统信任库 + ISRG), 设为 HttpsURLConnection 与 JVM 默认。
 *      影响面: DefaultHttpDataSource(ExoPlayer) / HttpURLConnection(下载器) 全部生效;
 *      MailFetcher 自建 SSLContext 实例不受影响, 邮件链路保持原样。
 */
object TlsCompat {

    private const val CERT_DIR = "certs"
    private val CERT_FILES = listOf("certs/isrg_root_x1.pem", "certs/isrg_root_x2.pem")

    /** 在 Application.onCreate 调用一次; 失败静默(保持系统默认行为, 不影响启动)。 */
    fun install(context: Context) {
        runCatching {
            val isrgTm = buildIsrgTrustManager(context)
            val systemTm = buildSystemTrustManager()
            val composite = CompositeTrustManager(systemTm, isrgTm)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(composite), null)
            SSLContext.setDefault(ctx)
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        }
    }

    /** 系统/厂商默认信任库的 TrustManager。 */
    private fun buildSystemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** 仅信任 assets 内 ISRG 根证书的 TrustManager。 */
    private fun buildIsrgTrustManager(context: Context): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        CERT_FILES.forEachIndexed { i, path ->
            runCatching {
                context.assets.open(path).use { input ->
                    val cert = cf.generateCertificate(input) as X509Certificate
                    ks.setCertificateEntry("isrg_$i", cert)
                }
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * 组合信任管理器: 依次尝试各 delegate, 任一通过即信任;
     * 全部失败时抛出最后一次异常。补充信任不放松校验 —— 校验强度与系统一致。
     */
    private class CompositeTrustManager(private vararg val delegates: X509TrustManager) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            var last: Exception? = null
            for (tm in delegates) {
                try {
                    tm.checkClientTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    last = e
                }
            }
            throw CertificateException("no delegate trusts client cert", last)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            var last: Exception? = null
            for (tm in delegates) {
                try {
                    tm.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    last = e
                }
            }
            throw CertificateException("no delegate trusts server cert", last)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
    }
}
