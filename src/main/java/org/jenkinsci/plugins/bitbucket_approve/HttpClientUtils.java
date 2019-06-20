package org.jenkinsci.plugins.bitbucket_approve;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

public class HttpClientUtils {

    public static OkHttpClient getHttpClient(Boolean ignoreSSL) {
        OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();

        if (ignoreSSL) {
            try {
                // Create a trust manager that does not validate certificate chains
                final TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                           String authType) throws CertificateException {
                            }
        
                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                           String authType) throws CertificateException {
                            }
        
                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
        
                // Install the all-trusting trust manager
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                // Create an ssl socket factory with our all-trusting manager
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
        
                httpClientBuilder
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier(new HostnameVerifier() {
                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                return true;
                            }
                        });
        
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        httpClientBuilder.connectTimeout(30, TimeUnit.SECONDS);
        httpClientBuilder.readTimeout(60, TimeUnit.SECONDS);

        OkHttpClient httpClient = httpClientBuilder.build();
        return httpClient;
    }
}