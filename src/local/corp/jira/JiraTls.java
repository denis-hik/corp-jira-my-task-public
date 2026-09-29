package local.corp.jira;
import javax.net.ssl.*;import java.net.Socket;import java.security.*;import java.security.cert.*;
final class JiraTls {
 static String approvalKey(String origin){return "corp.jira.tls.sha256."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(origin.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 static String savedApproval(String origin){return com.intellij.ide.util.PropertiesComponent.getInstance().getValue(approvalKey(origin));}
 static void saveApproval(String origin,String fingerprint){var properties=com.intellij.ide.util.PropertiesComponent.getInstance();if(fingerprint==null)properties.unsetValue(approvalKey(origin));else properties.setValue(approvalKey(origin),fingerprint);}
 record Failure(String fingerprint,String subject,String issuer,String reason){}
 volatile Failure failure;final SSLContext context;X509ExtendedTrustManager manager;
 static String fingerprint(X509Certificate certificate)throws CertificateException{try{return java.util.HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));}catch(GeneralSecurityException e){throw new CertificateException(e);}}
 JiraTls(String host,String accepted)throws GeneralSecurityException{
  TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init((KeyStore)null);X509ExtendedTrustManager delegate=null;for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509ExtendedTrustManager tm)delegate=tm;if(delegate==null)throw new GeneralSecurityException("TLS trust manager unavailable");final X509ExtendedTrustManager base=delegate;
  context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{manager=new X509ExtendedTrustManager(){
   boolean pinned(X509Certificate[] chain,String peer)throws CertificateException{return accepted!=null&&host.equalsIgnoreCase(peer)&&chain.length>0&&accepted.equals(fingerprint(chain[0]));}
   void failed(X509Certificate[] chain,CertificateException e){if(chain.length>0)try{var cert=chain[0];failure=new Failure(fingerprint(cert),cert.getSubjectX500Principal().getName(),cert.getIssuerX500Principal().getName(),e.getMessage());}catch(CertificateException ignored){}}
   public void checkServerTrusted(X509Certificate[] c,String a,SSLEngine engine)throws CertificateException{if(pinned(c,engine.getPeerHost()))return;try{base.checkServerTrusted(c,a,engine);}catch(CertificateException e){failed(c,e);throw e;}}
   public void checkServerTrusted(X509Certificate[] c,String a,Socket socket)throws CertificateException{String peer=socket instanceof SSLSocket ssl&&ssl.getHandshakeSession()!=null?ssl.getHandshakeSession().getPeerHost():"";if(pinned(c,peer))return;try{base.checkServerTrusted(c,a,socket);}catch(CertificateException e){failed(c,e);throw e;}}
   public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{base.checkServerTrusted(c,a);}catch(CertificateException e){failed(c,e);throw e;}}
   public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{base.checkClientTrusted(c,a);}public void checkClientTrusted(X509Certificate[] c,String a,Socket socket)throws CertificateException{base.checkClientTrusted(c,a,socket);}public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine engine)throws CertificateException{base.checkClientTrusted(c,a,engine);}public X509Certificate[] getAcceptedIssuers(){return base.getAcceptedIssuers();}
  }},null);
 }
}
