/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package io.cdap.cdap.datapipeline.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.cdap.cdap.api.service.http.AbstractSystemHttpServiceHandler;
import io.cdap.cdap.api.service.http.HttpServiceRequest;
import io.cdap.cdap.api.service.http.HttpServiceResponder;
import io.cdap.cdap.api.service.http.SystemHttpServiceContext;
import io.cdap.cdap.datapipeline.oauth.CredentialIsValidResponse;
import io.cdap.cdap.datapipeline.oauth.GetAccessTokenResponse;
import io.cdap.cdap.datapipeline.oauth.OAuthClientCredentials;
import io.cdap.cdap.datapipeline.oauth.OAuthProvider;
import io.cdap.cdap.datapipeline.oauth.OAuthRefreshToken;
import io.cdap.cdap.datapipeline.oauth.OAuthStore;
import io.cdap.cdap.datapipeline.oauth.OAuthStoreException;
import io.cdap.cdap.datapipeline.oauth.PutOAuthCredentialRequest;
import io.cdap.cdap.datapipeline.oauth.PutOAuthProviderRequest;
import io.cdap.cdap.datapipeline.oauth.RefreshTokenResponse;
import io.cdap.common.http.HttpRequest;
import io.cdap.common.http.HttpRequests;
import io.cdap.common.http.HttpResponse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth handler.
 */
public class OAuthHandler extends AbstractSystemHttpServiceHandler {
  private static final Logger LOG = LoggerFactory.getLogger(OAuthHandler.class);
  private static final String API_VERSION = "v1";
  private static final Gson GSON = new GsonBuilder()
    .setPrettyPrinting()
    .create();

  private OAuthStore oauthStore;

  @Override
  public void initialize(SystemHttpServiceContext context) throws Exception {
    super.initialize(context);
    this.oauthStore = new OAuthStore(context, context, context.getAdmin());
  }

  @GET
  @Path(API_VERSION + "/oauth/provider/{provider}/authurl")
  public void getAuthURL(HttpServiceRequest request, HttpServiceResponder responder,
                         @PathParam("provider") String provider,
                         @QueryParam("redirect_uri") String redirectURI,
                         @QueryParam("redirect_url") String redirectURL) {
    try {
      OAuthProvider oauthProvider = getProvider(provider);

      String formatURL = "%s";
      String loginUrl = oauthProvider.getLoginURL();
      if (!loginUrl.contains("?")) {
        formatURL += "?";
      } else if (!loginUrl.endsWith("&")) {
        formatURL += "&";
      }
      formatURL += "client_id=%s&redirect_uri=%s";

      // Maintaining backward compatibility for the apps using "redirect_url" parameter.
      if (redirectURI == null || redirectURI.isEmpty()) {
        redirectURI = redirectURL;
      }

      String response = String.format(
          formatURL, loginUrl, oauthProvider.getClientCredentials().getClientId(), redirectURI);
      responder.sendString(response);
    } catch (OAuthServiceException e) {
      e.respond(responder);
    }
  }

  @PUT
  @Path(API_VERSION + "/oauth/provider/{provider}")
  public void putOAuthProvider(HttpServiceRequest request, HttpServiceResponder responder,
                               @PathParam("provider") String oauthProvider,
                               @QueryParam("reuse_client_credentials") @DefaultValue("false")
                               Boolean reuseClientCredentials) {
    try {
      try {
        PutOAuthProviderRequest putOAuthProviderRequest = GSON.fromJson(
            StandardCharsets.UTF_8.decode(request.getContent()).toString(),
            PutOAuthProviderRequest.class);
        // Validate URLs
        URL loginURL = new URL(putOAuthProviderRequest.getLoginURL());
        URL tokenRefreshURL = new URL(putOAuthProviderRequest.getTokenRefreshURL());

        LOG.info("Received putOAuthProvider request with write_client_credentials = {}", reuseClientCredentials);
        OAuthClientCredentials clientCredentials = null;
        if (!reuseClientCredentials) {
          clientCredentials = OAuthClientCredentials.newBuilder()
                                                    .withClientId(putOAuthProviderRequest.getClientId())
                                                    .withClientSecret(putOAuthProviderRequest.getClientSecret())
                                                    .build();
        }
        OAuthProvider provider = OAuthProvider.newBuilder()
                                              .withName(oauthProvider)
                                              .withLoginURL(loginURL.toString())
                                              .withTokenRefreshURL(tokenRefreshURL.toString())
                                              .withClientCredentials(clientCredentials)
                                              .build();
        oauthStore.writeProvider(provider, reuseClientCredentials);
        responder.sendStatus(HttpURLConnection.HTTP_OK);
      } catch (JsonSyntaxException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid JSON: " + e.getMessage(), e);
      } catch (NullPointerException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid provider: " + e.getMessage(), e);
      } catch (MalformedURLException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid URL: " + e.getMessage(), e);
      } catch (OAuthStoreException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Failed to write to OAuth store", e);
      }
    } catch (OAuthServiceException e) {
      e.respond(responder);
    }
  }

  @PUT
  @Path(API_VERSION + "/oauth/provider/{provider}/credential/{credential}")
  public void putOAuthCredential(HttpServiceRequest request, HttpServiceResponder responder,
                                 @PathParam("provider") String provider,
                                 @PathParam("credential") String credentialId) {
    try {
      PutOAuthCredentialRequest putOAuthCredentialRequest;
      try {
        putOAuthCredentialRequest = GSON.fromJson(StandardCharsets.UTF_8.decode(request.getContent()).toString(),
                PutOAuthCredentialRequest.class);
        if (putOAuthCredentialRequest.getOneTimeCode() == null
            || putOAuthCredentialRequest.getOneTimeCode().isEmpty()) {
          throw new OAuthServiceException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid request: missing one-time code");
        }
        if (putOAuthCredentialRequest.getRedirectURI() == null
            || putOAuthCredentialRequest.getRedirectURI().isEmpty()) {
          throw new OAuthServiceException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid request: missing redirect URI");
        }
      } catch (JsonSyntaxException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid JSON: " + e.getMessage(), e);
      }

      OAuthProvider oauthProvider = getProvider(provider);

      HttpResponse response;
      try {
        response = HttpRequests.execute(createGetRefreshTokenRequest(
            oauthProvider,
            putOAuthCredentialRequest.getOneTimeCode(),
            putOAuthCredentialRequest.getRedirectURI()));
      } catch (IOException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Error while fetching refresh token", e);
      }

      if (response.getResponseCode() != 200) {
        throw new OAuthServiceException(
          response.getResponseCode(),
            "Request for refresh token did not return 200. Response code: "
                + response.getResponseCode()
                + " , response message: "
                + response.getResponseMessage()
                + " , respone body: "
                + response.getResponseBodyAsString());
      }

      RefreshTokenResponse refreshTokenResponse;
      try {
        refreshTokenResponse = GSON.fromJson(response.getResponseBodyAsString(), RefreshTokenResponse.class);
      } catch (JsonSyntaxException e) {
        throw new OAuthServiceException(
            HttpURLConnection.HTTP_INTERNAL_ERROR, "Failed to parse JSON: " + e.getMessage(), e);
      }

      if (refreshTokenResponse.getRefreshToken() == null || refreshTokenResponse.getRefreshToken().isEmpty()) {
        throw new OAuthServiceException(
            HttpURLConnection.HTTP_INTERNAL_ERROR, "Refresh token response body did not contain refresh token");
      }

      try {
        OAuthRefreshToken refreshToken = OAuthRefreshToken.newBuilder()
            .withRefreshToken(refreshTokenResponse.getRefreshToken())
            .withRedirectURI(putOAuthCredentialRequest.getRedirectURI())
            .build();
        oauthStore.writeRefreshToken(provider, credentialId, refreshToken);
      } catch (NullPointerException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, e.getMessage(), e);
      } catch (OAuthStoreException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Failed to write refresh token", e);
      }

      responder.sendStatus(HttpURLConnection.HTTP_OK);
    } catch (OAuthServiceException e) {
      e.respond(responder);
    }
  }

  @GET
  @Path(API_VERSION + "/oauth/provider/{provider}/credential/{credential}")
  public void getOAuthCredential(HttpServiceRequest request, HttpServiceResponder responder,
                                 @PathParam("provider") String provider,
                                 @PathParam("credential") String credentialId) {
    try {
      OAuthProvider oauthProvider = getProvider(provider);
      OAuthRefreshToken refreshToken = getRefreshToken(provider, credentialId);

      HttpResponse response;
      try {
        response = HttpRequests.execute(createGetAccessTokenRequest(oauthProvider, refreshToken.getRefreshToken()));
      } catch (IOException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Failed to fetch refresh token", e);
      }

      if (response.getResponseCode() != 200) {
        throw new OAuthServiceException(
          response.getResponseCode(),
            "Request for refresh token did not return 200. Response code: "
                + response.getResponseCode()
                + " , response message: "
                + response.getResponseMessage()
                + " , respone body: "
                + response.getResponseBodyAsString());
      }

      RefreshTokenResponse refreshTokenResponse;
      try {
        refreshTokenResponse = GSON.fromJson(response.getResponseBodyAsString(), RefreshTokenResponse.class);
      } catch (JsonSyntaxException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Error parsing JSON response", e);
      }
      if (refreshTokenResponse.getAccessToken() == null || refreshTokenResponse.getAccessToken().isEmpty()) {
        throw new OAuthServiceException(
            HttpURLConnection.HTTP_INTERNAL_ERROR, "Refresh token response body does not have refresh token");
      }

      responder.sendString(GSON.toJson(
          new GetAccessTokenResponse(refreshTokenResponse.getAccessToken(), refreshTokenResponse.getInstanceURL())));
    } catch (OAuthServiceException e) {
      e.respond(responder);
    }
  }

  @GET
  @Path(API_VERSION + "/oauth/provider/{provider}/credential/{credential}/valid")
  public void getOAuthCredentialValidity(HttpServiceRequest request, HttpServiceResponder responder,
                                         @PathParam("provider") String provider,
                                         @PathParam("credential") String credentialId) {
    try {
      OAuthProvider oauthProvider = getProvider(provider);
      OAuthRefreshToken refreshToken = getRefreshToken(provider, credentialId);

      HttpResponse response;
      try {
        response = HttpRequests.execute(createGetAccessTokenRequest(oauthProvider, refreshToken.getRefreshToken()));
      } catch (IOException e) {
        throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Error while fetching refresh token", e);
      }

      responder.sendString(GSON.toJson(new CredentialIsValidResponse(checkCredIsValid(response))));
    } catch (OAuthServiceException e) {
      e.respond(responder);
    }
  }

  private boolean checkCredIsValid(HttpResponse response) throws OAuthServiceException {
    if (response.getResponseCode() != 200) {
      return false;
    }

    RefreshTokenResponse refreshTokenResponse;
    try {
      refreshTokenResponse = GSON.fromJson(response.getResponseBodyAsString(), RefreshTokenResponse.class);
    } catch (JsonSyntaxException e) {
      throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Failed to parse JSON", e);
    }

    return !(refreshTokenResponse.getAccessToken() == null || refreshTokenResponse.getAccessToken().isEmpty());
  }

  private HttpRequest createGetRefreshTokenRequest(OAuthProvider provider, String code, String redirectURI)
      throws OAuthServiceException {
    OAuthClientCredentials clientCreds = provider.getClientCredentials();
    try {
      return HttpRequest.post(new URL(provider.getTokenRefreshURL()))
          .withBody(String.format(
              "code=%s&redirect_uri=%s&client_id=%s&client_secret=%s&grant_type=authorization_code",
              code, redirectURI, clientCreds.getClientId(), clientCreds.getClientSecret()))
          .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED)
          .build();
    } catch (MalformedURLException e) {
      throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Malformed URL", e);
    }
  }

  private HttpRequest createGetAccessTokenRequest(OAuthProvider provider, String refreshToken)
      throws OAuthServiceException {
    OAuthClientCredentials clientCreds = provider.getClientCredentials();
    try {
      return HttpRequest.post(new URL(provider.getTokenRefreshURL()))
          .withBody(
              String.format("grant_type=refresh_token&client_id=%s&client_secret=%s&refresh_token=%s",
                clientCreds.getClientId(),
                clientCreds.getClientSecret(),
                refreshToken))
          .build();
    } catch (MalformedURLException e) {
      throw new OAuthServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, "Malformed URL", e);
    }
  }

  private OAuthProvider getProvider(String provider) throws OAuthServiceException {
    try {
      Optional<OAuthProvider> providerOptional = oauthStore.getProvider(provider);
      if (providerOptional.isPresent()) {
        return providerOptional.get();
      }
      throw new OAuthServiceException(HttpURLConnection.HTTP_NOT_FOUND, "Unknown OAuth provider: " + provider);
    } catch (OAuthStoreException e) {
      throw new OAuthServiceException(
          HttpURLConnection.HTTP_INTERNAL_ERROR, "Error attempting to retrieve OAuth provider", e);
    }
  }

  private OAuthRefreshToken getRefreshToken(String provider, String credentialId) throws OAuthServiceException {
    try {
      Optional<OAuthRefreshToken> refreshTokenOptional = oauthStore.getRefreshToken(provider, credentialId);
      if (refreshTokenOptional.isPresent()) {
        return refreshTokenOptional.get();
      }
      throw new OAuthServiceException(HttpURLConnection.HTTP_NOT_FOUND, "Unknown OAuth credential: " + credentialId);
    } catch (OAuthStoreException e) {
      throw new OAuthServiceException(
          HttpURLConnection.HTTP_INTERNAL_ERROR, "Failed to read OAuth credential from secure store", e);
    }
  }

  private static class OAuthServiceException extends Exception {
    private final int status;

    OAuthServiceException(int status, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    OAuthServiceException(int status, String message) {
      super(message);
      this.status = status;
    }

    void respond(HttpServiceResponder responder) {
      if (status == HttpURLConnection.HTTP_INTERNAL_ERROR) {
        LOG.error("An internal error has occurred", this);
        responder.sendError(status, "Internal error");
      } else {
        responder.sendError(status, getMessage());
      }
    }
  }
}
