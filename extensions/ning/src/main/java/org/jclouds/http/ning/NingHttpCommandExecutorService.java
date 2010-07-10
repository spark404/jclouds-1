/**
 *
 * Copyright (C) 2010 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.jclouds.http.ning;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.inject.Singleton;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.HttpHeaders;

import org.jclouds.encryption.EncryptionService;
import org.jclouds.http.HttpCommand;
import org.jclouds.http.HttpCommandExecutorService;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpRequestFilter;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.Payload;
import org.jclouds.http.Payloads;
import org.jclouds.http.handlers.DelegatingErrorHandler;
import org.jclouds.http.handlers.DelegatingRetryHandler;
import org.jclouds.http.internal.BaseHttpCommandExecutorService;

import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.Closeables;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import com.ning.http.client.Request.EntityWriter;

/**
 * Todo Write me
 * 
 * @author Sam Tunnicliffe
 * @author Adrian Cole
 */
public class NingHttpCommandExecutorService implements HttpCommandExecutorService {

   public static final String USER_AGENT = "jclouds/1.0 ning http/1.0.0";

   private final AsyncHttpClient client;
   private final ConvertToNingRequest convertToNingRequest;
   private final ConvertToJCloudsResponse convertToJCloudsResponse;
   private final DelegatingRetryHandler retryHandler;
   private final DelegatingErrorHandler errorHandler;

   @Inject
   public NingHttpCommandExecutorService(AsyncHttpClient client,
            ConvertToNingRequest convertToNingRequest,
            ConvertToJCloudsResponse convertToJCloudsResponse, DelegatingRetryHandler retryHandler,
            DelegatingErrorHandler errorHandler) {
      this.client = client;
      this.convertToNingRequest = convertToNingRequest;
      this.convertToJCloudsResponse = convertToJCloudsResponse;
      this.retryHandler = retryHandler;
      this.errorHandler = errorHandler;
   }

   @Override
   public ListenableFuture<HttpResponse> submit(HttpCommand command) {
      try {
         for (;;) {
            Future<Response> responseF = client.executeRequest(convertToNingRequest.apply(command
                     .getRequest()));
            final HttpResponse httpResponse = convertToJCloudsResponse.apply(responseF.get());
            int statusCode = httpResponse.getStatusCode();
            if (statusCode >= 300) {
               if (retryHandler.shouldRetryRequest(command, httpResponse)) {
                  continue;
               } else {
                  errorHandler.handleError(command, httpResponse);
                  return wrapAsFuture(httpResponse);
               }
            } else {
               return wrapAsFuture(httpResponse);
            }
         }

      } catch (IOException e) {
         throw Throwables.propagate(e);
      } catch (InterruptedException e) {
         throw Throwables.propagate(e);
      } catch (ExecutionException e) {
         throw Throwables.propagate(e);
      }
   }

   private ListenableFuture<HttpResponse> wrapAsFuture(final HttpResponse httpResponse) {
      return Futures.makeListenable(new AbstractFuture<HttpResponse>() {
         @Override
         public HttpResponse get() throws InterruptedException, ExecutionException {
            return httpResponse;
         }
      });
   }

   @Singleton
   public static class ConvertToNingRequest implements Function<HttpRequest, Request> {
      private final EncryptionService encryptionService;

      @Inject
      ConvertToNingRequest(EncryptionService encryptionService) {
         this.encryptionService = encryptionService;
      }

      private static class PayloadEntityWriter implements EntityWriter {
         private final Payload payload;

         public PayloadEntityWriter(Payload payload) {
            this.payload = payload;
         }

         @Override
         public void writeEntity(OutputStream out) throws IOException {
            payload.writeTo(out);

         }
      }

      public Request apply(HttpRequest request) {

         for (HttpRequestFilter filter : request.getFilters()) {
            filter.filter(request);
         }

         AsyncHttpClient client = new AsyncHttpClient();
         AsyncHttpClient.BoundRequestBuilder nativeRequestBuilder;
         String endpoint = request.getEndpoint().toASCIIString();
         if (request.getMethod().equals(HttpMethod.HEAD)) {
            nativeRequestBuilder = client.prepareHead(endpoint);
         } else if (request.getMethod().equals(HttpMethod.GET)) {
            nativeRequestBuilder = client.prepareGet(endpoint);
         } else if (request.getMethod().equals(HttpMethod.DELETE)) {
            nativeRequestBuilder = client.prepareDelete(endpoint);
         } else if (request.getMethod().equals(HttpMethod.PUT)) {
            nativeRequestBuilder = client.preparePut(endpoint);
         } else if (request.getMethod().equals(HttpMethod.POST)) {
            nativeRequestBuilder = client.preparePost(endpoint);
         } else {
            throw new UnsupportedOperationException(request.getMethod());
         }
         Payload payload = request.getPayload();
         if (payload != null) {
            boolean chunked = "chunked".equals(request.getFirstHeaderOrNull("Transfer-Encoding"));

            if (request.getPayload().getContentMD5() != null)
               nativeRequestBuilder.addHeader("Content-MD5", encryptionService.base64(request
                        .getPayload().getContentMD5()));
            if (request.getPayload().getContentType() != null)
               nativeRequestBuilder.addHeader(HttpHeaders.CONTENT_TYPE, request.getPayload()
                        .getContentType());
            if (!chunked) {
               Long length = checkNotNull(request.getPayload().getContentLength(),
                        "payload.getContentLength");
               nativeRequestBuilder.addHeader(HttpHeaders.CONTENT_LENGTH, length.toString());
            }
            setPayload(nativeRequestBuilder, payload);
         } else {
            nativeRequestBuilder.addHeader(HttpHeaders.CONTENT_LENGTH, "0");
         }

         nativeRequestBuilder.addHeader(HttpHeaders.USER_AGENT, USER_AGENT);
         for (String header : request.getHeaders().keySet()) {
            for (String value : request.getHeaders().get(header)) {
               nativeRequestBuilder.addHeader(header, value);
            }
         }

         return nativeRequestBuilder.build();
      }

      void setPayload(AsyncHttpClient.BoundRequestBuilder requestBuilder, Payload payload) {
         requestBuilder.setBody(new PayloadEntityWriter(payload));
      }
   }

   @Singleton
   public static class ConvertToJCloudsResponse implements Function<Response, HttpResponse> {
      private final HttpUtils utils;

      @Inject
      ConvertToJCloudsResponse(HttpUtils utils) {
         this.utils = utils;
      }

      public HttpResponse apply(Response nativeResponse) {

         InputStream in = null;
         try {
            in = BaseHttpCommandExecutorService.consumeOnClose(nativeResponse
                     .getResponseBodyAsStream());
         } catch (IOException e) {
            Closeables.closeQuietly(in);
            propagate(e);
            assert false : "should have propagated exception";
         }

         Payload payload = in != null ? Payloads.newInputStreamPayload(in) : null;
         HttpResponse response = new HttpResponse(nativeResponse.getStatusCode(), nativeResponse
                  .getStatusText(), payload);
         Multimap<String, String> headers = LinkedHashMultimap.create();
         for (Entry<String, List<String>> header : nativeResponse.getHeaders()) {
            headers.putAll(header.getKey(), header.getValue());
         }
         utils.setPayloadPropertiesFromHeaders(headers, response);
         return response;
      }
   }
}
