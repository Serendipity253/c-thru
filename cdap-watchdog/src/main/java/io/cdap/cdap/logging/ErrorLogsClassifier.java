/*
 * Copyright © 2024 Cask Data, Inc.
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
 */

package io.cdap.cdap.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import io.cdap.cdap.api.dataset.lib.CloseableIterator;
import io.cdap.cdap.api.exception.ErrorType;
import io.cdap.cdap.api.exception.FailureDetailsProvider;
import io.cdap.cdap.api.exception.WrappedStageException;
import io.cdap.cdap.common.conf.Constants.Logging;
import io.cdap.cdap.logging.read.LogEvent;
import io.cdap.cdap.proto.ErrorClassificationResponse;
import io.cdap.http.HttpResponder;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import joptsimple.internal.Strings;

/**
 * Classifies error logs and returns {@link ErrorClassificationResponse}.
 * TODO -
 *  - Add rule based classification.
 */
public class ErrorLogsClassifier {
  private static final Gson GSON = new Gson();
  private static final String DATAPROC_RUNTIME_EXCEPTION =
      "io.cdap.cdap.runtime.spi.provisioner.dataproc.DataprocRuntimeException";
  private static final ImmutableList<String> ALLOWLIST_CLASSES =
      ImmutableList.<String>builder().add(DATAPROC_RUNTIME_EXCEPTION).build();
  private static final LoadingCache<String, Boolean> cache = CacheBuilder.newBuilder()
      .maximumSize(5000)
      .build(new CacheLoader<String, Boolean>() {
        @Override
        public Boolean load(String className) {
          try {
            return ALLOWLIST_CLASSES.contains(className)
                || FailureDetailsProvider.class.isAssignableFrom(Class.forName(className));
          } catch (Exception e) {
            return false; // Handle missing class
          }
        }
      });

  private static boolean isFailureDetailsProviderInstance(String className) {
    try {
      return cache.get(className);
    } catch (Exception e) {
      return false; // Handle any unexpected errors
    }
  }

  /**
   * Classifies error logs and returns {@link ErrorClassificationResponse}.
   *
   * @param logIter Logs Iterator that can be closed.
   * @param responder The HttpResponder.
   */
  public void classify(CloseableIterator<LogEvent> logIter, HttpResponder responder) {
    Map<String, ErrorClassificationResponse> responseMap = new HashMap<>();
    Set<ErrorClassificationResponse> responseSet = new HashSet<>();
    while (logIter.hasNext()) {
      ILoggingEvent logEvent = logIter.next().getLoggingEvent();
      Map<String, String> mdc = logEvent.getMDCPropertyMap();
      String stageName = mdc.get(Logging.TAG_FAILED_STAGE);
      IThrowableProxy throwableProxy = logEvent.getThrowableProxy();
      while (throwableProxy != null) {
        if (isFailureDetailsProviderInstance(throwableProxy.getClassName())) {
          populateResponse(throwableProxy, mdc, stageName, responseMap, responseSet);
        }
        throwableProxy = throwableProxy.getCause();
      }
    }
    List<ErrorClassificationResponse> responses = new ArrayList<>(responseMap.values());
    responses.addAll(responseSet);
    responder.sendJson(HttpResponseStatus.OK, GSON.toJson(responses));
  }

  private void populateResponse(IThrowableProxy throwableProxy,
      Map<String, String> mdc, String stageName,
      Map<String, ErrorClassificationResponse> responseMap,
      Set<ErrorClassificationResponse> responseSet) {
    boolean stageNotPresent = Strings.isNullOrEmpty(stageName);
    if (stageNotPresent) {
      responseSet.add(getClassificationResponse(stageName, mdc, throwableProxy,
          mdc.get(Logging.TAG_ERROR_CATEGORY)));
      return;
    }

    String errorCategory = String.format("%s-'%s'", mdc.get(Logging.TAG_ERROR_CATEGORY), stageName);
    responseMap.putIfAbsent(stageName,
        getClassificationResponse(stageName, mdc, throwableProxy, errorCategory));

    if (WrappedStageException.class.getName().equals(
        responseMap.get(stageName).getThrowableClassName())) {
      // WrappedStageException takes lower precedence than other FailureDetailsProvider exceptions.
      responseMap.put(stageName,
          getClassificationResponse(stageName, mdc, throwableProxy, errorCategory));
    }
  }

  private ErrorClassificationResponse getClassificationResponse(String stageName,
      Map<String, String> mdc, IThrowableProxy throwableProxy, String errorCategory) {
    return new ErrorClassificationResponse.Builder()
        .setStageName(stageName)
        .setErrorCategory(errorCategory)
        .setErrorReason(mdc.get(Logging.TAG_ERROR_REASON))
        .setErrorMessage(throwableProxy.getMessage())
        .setErrorType(mdc.get(Logging.TAG_ERROR_TYPE) == null ? ErrorType.UNKNOWN.name() :
            mdc.get(Logging.TAG_ERROR_TYPE))
        .setDependency(mdc.get(Logging.TAG_DEPENDENCY) == null ? String.valueOf(false) :
            mdc.get(Logging.TAG_DEPENDENCY))
        .setErrorCodeType(mdc.get(Logging.TAG_ERROR_CODE_TYPE))
        .setErrorCode(mdc.get(Logging.TAG_ERROR_CODE))
        .setSupportedDocumentationUrl(mdc.get(Logging.TAG_SUPPORTED_DOC_URL))
        .setThrowableClassName(throwableProxy.getClassName())
        .build();
  }
}
