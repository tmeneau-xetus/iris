package com.xetus.oss.iris;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.http.Header;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.xetus.oss.iris.http.JsonRpcHttpKerberosClient;

/**
 * Manages authenticating against the FreeIPA server and establishing
 * a valid session for use with the target freeIPA server's JSON-RPC
 * API. This class handles authenticating against the two separate
 * session establishing mechanisms exposed by the FreeIPA portal, namely:<ol>
 * 
 *  <li>{@link #getKerberosClient()} authenticates using the keytab
 *  and principal configured via the {@link FreeIPAConfig}; and
 *  <li>{@link #getSessionClient(String, String, String)} authenticates
 *  using the passed user name and password (and optionally realm).
 *  
 * </ol>
 * 
 * <h5>1. Authenticating Using Kerberos</h5>
 * Kerberos authentication uses the keytab and principal configured via
 * the {@link FreeIPAConfig#getKeytabPath()} and {@link 
 * FreeIPAConfig#getPrincipal()} respective configuration options.
 * 
 * <h5>2. Authenticating Using Account Credentials</h5>
 * Authentication via account credentials should be as simple as passing in
 * the username and password to the {@link #getSessionClient(String, String, 
 * String)} method.
 * 
 * <h5>Notes</h5>
 * The {@link FreeIPAClient} returned in both of the above scenarios will
 * have the same access privileges as the accounts used to authenticate them.
 */
class FreeIPAAuthenticationManager {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(FreeIPAAuthenticationManager.class);
  
  FreeIPAConfig config;

  PoolingHttpClientConnectionManager cm = 
      new PoolingHttpClientConnectionManager(30, TimeUnit.SECONDS);

  FreeIPAAuthenticationManager(FreeIPAConfig c) {
    this.config = c;
    this.cm.setDefaultMaxPerRoute(10);
    this.cm.setMaxTotal(30);
  }
  /**
   * @return a {@link JsonRpcHttpClient} configured against the FreeIPA
   * instance's Kerberos RPC API. The returned client is guaranteed to 
   * be successfully authenticated against the FreeIPA instance using 
   * the principal and keytab specified through the {@link 
   * FreeIPAConfig#getPrincipal()} and {@link FreeIPAConfig#getKeytabPath()}
   * configurations.
   */
  public JsonRpcHttpClient getRPCKerberosClient() 
                           throws InvalidKeytabException {
    if (config.getKeytabPath() == null) {
      throw new IllegalStateException(
        "Cannot create Kerberos client if keytab path is not configured"
      );
    }
    
    if (config.getPrincipal() == null) {
      throw new IllegalStateException(
        "Cannot create Kerberos client if principal is not configured"
      );
    }
    
    Map<String, String> headers = buildClientHeaders();
    headers.put("referrer", getIpaUrl("/ipa").toString());
    return new JsonRpcHttpKerberosClient(
        config.getRPCObjectMapper(),
        getIpaUrl("/ipa/json"),
        headers,
        config.getKeytabPath(),
        config.getPrincipal()
      );
  }

  /**
   * @return {@link FreeIPAClient} configured with the session ID and
   * the FreeIPA server's JSON-RPC endpoint. Note that a half-hearted
   * check will be made to ensure a session has been retrieved before
   * creating and returning a new client. 
   * @throws PasswordExpiredException 
   * @throws InvalidUserOrRealmException 
   * @throws InvalidPasswordException 
   */
  public JsonRpcHttpClient getSessionClient(String user, String pass) 
                           throws PasswordExpiredException, 
                                  InvalidPasswordException, 
                                  InvalidUserOrRealmException {
    return getSessionClient(user, pass, null);
  }
  
  /**
   * @return {@link FreeIPAClient} configured with the session ID and
   * the FreeIPA server's JSON-RPC endpoint. Note that a half-hearted
   * check will be made to ensure a session has been retrieved before
   * creating and returning a new client. 
   * @throws PasswordExpiredException 
   * @throws InvalidUserOrRealmException 
   * @throws InvalidPasswordException 
   */
  public JsonRpcHttpClient getSessionClient(String user,
                                            String pass, 
                                            String realm) 
                           throws PasswordExpiredException, 
                                  InvalidPasswordException, 
                                  InvalidUserOrRealmException {
    Map<String, String> headers = buildClientHeaders();
    headers.put("referrer", getIpaUrl("/ipa").toString());
    headers.put("Cookie", "ipa_session=" + connect(user, pass, realm));
    return new JsonRpcHttpClient(
      config.getRPCObjectMapper(),
      getIpaUrl("/ipa/session/json"),
      headers
    );
  }
  
  public String connect(String user, String pass) 
                throws PasswordExpiredException, 
                       InvalidPasswordException, 
                       InvalidUserOrRealmException {
    return connect(user, pass, null);
  }

  /**
   * Attempts to establish a connection using the passed username, password
   * and realm. If the realm is not passed, the username is expected to
   * contain the realm (e.g. user@REALM.COM). Returns the session Cookie if 
   * successfully authenticated.
   * 
   * @param user
   * @param pass
   * 
   * @throws PasswordExpiredException if the user's password has expired and
   * needs to be reset
   * @throws InvalidPasswordException if the supplied password is invalid for
   * the supplied user and realm
   * @throws InvalidUserOrRealmException if the supplied username and/or realm
   * is invalid for the host
   * @throws RuntimeException if any other error response is encountered from
   * FreeIPA
   */
  public String connect(String user, String pass, String realm) 
         throws PasswordExpiredException, InvalidPasswordException, InvalidUserOrRealmException {

    user = getUser(user, realm);
    
    URI target;
    try {
      target = getIpaUrl("/ipa/session/login_password").toURI();
    } catch(URISyntaxException e) {
      throw new IllegalArgumentException("Invalid hostname", e);
    }
    LOGGER.trace("Connecting for user: {} at target: {}", user, target);

    HttpPost post = getPost(target);
    List<BasicNameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("user", user));
    params.add(new BasicNameValuePair("password", pass));
    try {
      post.setEntity(new UrlEncodedFormEntity(params));
    } catch(UnsupportedEncodingException e) {
      LOGGER.error("Failed to encoded connction params", e);
      throw new RuntimeException(e);
    }

    CookieStore cookieStore = new BasicCookieStore();
    CloseableHttpResponse response;
    String responseText;
    try {
      response = execute(post, cookieStore);
      responseText = getResponse(response);
    } catch (ClientProtocolException c) {
      LOGGER.error("Failed to negotiate client protocol", c);
      throw new RuntimeException(c);
    } catch(IOException e) {
      throw new RuntimeException(e);
    }

    try {
      if (response.getStatusLine().getStatusCode() != 200) {
        if (response.getStatusLine().getStatusCode() == 401) {

          Header[] reasons = response.getHeaders("X-IPA-Rejection-Reason");

          if (reasons.length > 0) {
            if (reasons[0].getValue() == "password-expired") {
              throw new PasswordExpiredException();
            }

            if (reasons[0].getValue() == "invalid-password") {
              throw new InvalidPasswordException();
            }

            if (reasons[0].getValue() == "denied") {
              throw new InvalidUserOrRealmException();
            }

          }
        }
        
        throw new RuntimeException(
            buildExceptionString(response, responseText)
        );
      }
    } finally {
      try {
        response.close();
      } catch(IOException e) {
        LOGGER.error("Failed to close the repsonse stream", e);
      }
    }
    
    Optional<Cookie> sessionCookie = cookieStore
        .getCookies().stream().filter( c -> c.getName() == "ipa_session")
                              .findFirst();
    
    return sessionCookie.isPresent() ? 
              sessionCookie.get().getValue() : null; 
  }

  /**
   * Resets the user's password. Note that this need only be used in cases
   * where the user is unable to establish a session because their password
   * has expired. This includes scenarios where their password has been
   * reset by an administrator and FreeIPA immediately expires their 
   * password to force a reset.
   * 
   * @param user
   * @param oldPass
   * @param newPass
   * @param otp
   * @param realm
   * 
   * @throws PasswordExpiredException if the user's password expired
   * @throws InvalidPasswordException if an invalid oldPass was supplied
   * @throws PasswordPolicyViolationException if the newPass did not meet
   *  the password policy restrictions imposed on the server
   * @throws InvalidUserOrRealmException 
   * @throws RuntimeException if the session timed out or an unexpected
   * error occured server-side
   * 
   */
  public JsonRpcHttpClient resetPassword(String user, 
                                  String oldPass, 
                                  String newPass,
                                  String realm,
                                  String otp) 
                           throws PasswordExpiredException, 
                                  InvalidPasswordException, 
                                  PasswordPolicyViolationException, 
                                  InvalidUserOrRealmException {

    // for some reason here FreeIPA does not expect to have the realm passed
    user = user.replace("@.*", "");
    
    URI target;
    try {
      target = getIpaUrl("/ipa/session/change_password").toURI();
    } catch(URISyntaxException e) {
      throw new IllegalArgumentException("Invalid hostname", e);
    }
    
    HttpPost post = getPost(target);

    List<BasicNameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair("user", user));
    params.add(new BasicNameValuePair("old_password", oldPass));
    params.add(new BasicNameValuePair("new_password", newPass));

    if (otp != null) {
      params.add(new BasicNameValuePair("otp", otp));
    }

    try {
      post.setEntity(new UrlEncodedFormEntity(params));
    } catch(UnsupportedEncodingException e) {
      LOGGER.error("Failed to encoded connction params", e);
      throw new RuntimeException(e);
    }
    
    CloseableHttpResponse response;
    String responseText;
    try {
      response = execute(post, new BasicCookieStore());
      responseText = getResponse(response);
    } catch (ClientProtocolException c) {
      LOGGER.error("Failed to negotiate client protocol", c);
      throw new RuntimeException(c);
    } catch(IOException e) {
      throw new RuntimeException(e);
    }

    try {
      if (response.getStatusLine().getStatusCode() != 200) {
        if (response.getStatusLine().getStatusCode() == 401) {
  
          Header[] reasons = response.getHeaders("X-IPA-Rejection-Reason");
          if (reasons.length > 0 && 
              reasons[0].getValue() == "password-expired") {
            throw new PasswordExpiredException();
          }
        }
  
        throw new RuntimeException(
            buildExceptionString(response, responseText)
        );
      }
    } finally {
      try {
        response.close();
      } catch(IOException e) {
        LOGGER.error("Failed to close response", e);
      }
    }

    Header[] reasons = response.getHeaders("X-IPA-Pwchange-Result");
    for (Header header : reasons) {
      if (header.getValue() == "invalid-password") {
        throw new InvalidPasswordException("Invalid password");
      } else
      if (header.getValue() == "policy-error") {
        Header[] policyErrors = response
          .getHeaders("X-IPA-Pwchange-Policy-Error");
        throw new PasswordPolicyViolationException(
            policyErrors[0].getValue()
        );
      }
      if (header.getValue() == "error") {
        throw new RuntimeException("Password change failed");
      }
    }

    return this.getSessionClient(user, newPass, realm);
  }

  private String getUser(String user, String realm) {
    if (realm == null && user.matches("@")) {
      if (config.getRealm() == null) {
        throw new IllegalArgumentException("Realm is required to open a "
        + "connection to the FreeIPA instance");
      }
      realm = config.getRealm();
    }

    if (!user.matches("@")) {
      user = user + "@" + realm;
    }

    return user;
  }

  private HttpPost getPost(URI target) {
    HttpPost post = new HttpPost(target);
    post.addHeader("Accept", ContentType.APPLICATION_XML.getMimeType());
    post.addHeader("Content-Type",
        ContentType.APPLICATION_FORM_URLENCODED.getMimeType());
    return post;
  }

  private CloseableHttpResponse execute(HttpPost post, 
                                        CookieStore cookieStore) 
                                throws ClientProtocolException, 
                                       IOException {
    CloseableHttpClient client = HttpClientBuilder
          .create()
          .useSystemProperties()
          .setConnectionManager(cm)
          .setDefaultCookieStore(cookieStore)
          .setDefaultSocketConfig(
             SocketConfig.custom()
                         .setSoTimeout(30*1000)
                         .setTcpNoDelay(true)
                         .build())
          .build();
    
    CloseableHttpResponse response = client.execute(post);
    LOGGER.debug(
        "Post response:\ncode: {}\nheaders: {}\ncontent: " +
        "{}\n-----------\n\n",
        response.getStatusLine().getStatusCode(),
        response.getAllHeaders(),
        getResponse(response)
    );
    
    return response;
  }

  private URL getIpaUrl(String path) {
    String url = "https://" + config.getHostname();
    if (path != null) {
      url += path;
    }
    
    try {
      return new URL(url);
    } catch(MalformedURLException e) {
      throw new IllegalArgumentException("Invalid hostname", e);
    }
  }
  
  private Map<String, String> buildClientHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("referrer", getIpaUrl("/ipa").toString());
    return headers;
  }
  
  private String getResponse(CloseableHttpResponse response) 
                 throws IllegalStateException, 
                        IOException {
    if (response.getEntity() == null || 
        response.getEntity().getContent() == null) {
      return null;
    }
    
    String responseString = "";
    for (int i = 0; i < response.getEntity().getContentLength(); i++) { 
      responseString += Character.toString(
          (char) response.getEntity().getContent().read()
      ); 
    }
    return responseString;
  }

  private String buildExceptionString(CloseableHttpResponse response, 
                                      String text) {
    StringBuffer sb = new StringBuffer(
        "Encountered unexpected response from FreeIPA; details:\\n\\n"
    );
    sb.append("code: ");
    sb.append(response.getStatusLine().getStatusCode() + "\n");
    sb.append("headers: " + response.getAllHeaders() + "\n");
    sb.append("content: " + text + "\n");
    sb.append("-----------\n\n");
    return sb.toString();
  }

}