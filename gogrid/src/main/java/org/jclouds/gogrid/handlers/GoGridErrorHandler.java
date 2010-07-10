/**
 *
 * Copyright (C) 2010 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 */
package org.jclouds.gogrid.handlers;

import static org.jclouds.http.HttpUtils.releasePayload;

import java.util.Set;

import org.jclouds.gogrid.GoGridResponseException;
import org.jclouds.gogrid.domain.internal.ErrorResponse;
import org.jclouds.gogrid.functions.ParseErrorFromJsonResponse;
import org.jclouds.http.HttpCommand;
import org.jclouds.http.HttpErrorHandler;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.HttpResponseException;
import org.jclouds.http.Payload;
import org.jclouds.rest.AuthorizationException;
import org.jclouds.rest.ResourceNotFoundException;

import com.google.common.collect.Iterables;
import com.google.inject.Inject;

/**
 * @author Oleksiy Yarmula
 */
public class GoGridErrorHandler implements HttpErrorHandler {

   private final ParseErrorFromJsonResponse errorParser;

   @Inject
   public GoGridErrorHandler(ParseErrorFromJsonResponse errorParser) {
      this.errorParser = errorParser;
   }

   @Override
   public void handleError(HttpCommand command, HttpResponse response) {
      try {
         Exception exception = new HttpResponseException(command, response);
         Set<ErrorResponse> errors = parseErrorsFromContentOrNull(response.getPayload());
         switch (response.getStatusCode()) {
            case 400:
               if (Iterables.get(errors, 0).getMessage().indexOf("No object found") != -1) {
                  exception = new ResourceNotFoundException(Iterables.get(errors, 0).getMessage(),
                           exception);
                  break;
               }
            case 403:

               exception = new AuthorizationException(command.getRequest(), errors != null ? errors
                        .toString() : response.getStatusLine());
               break;
            default:
               exception = errors != null ? new GoGridResponseException(command, response, errors)
                        : new HttpResponseException(command, response);
         }
         command.setException(exception);
      } finally {
         releasePayload(response);
      }
   }

   Set<ErrorResponse> parseErrorsFromContentOrNull(Payload payload) {
      if (payload != null) {
         try {
            return errorParser.apply(payload.getInput());
         } catch (/* Parsing */Exception e) {
            return null;
         }
      }
      return null;
   }
}
