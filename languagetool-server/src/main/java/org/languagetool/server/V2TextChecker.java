/* LanguageTool, a natural language style checker
 * Copyright (C) 2016 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.server;

import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;
import org.languagetool.*;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.rules.RuleMatch;
import org.languagetool.tools.StringTools;
import org.languagetool.tools.RuleMatchesAsJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.languagetool.server.ServerTools.setCommonHeaders;

/**
 * Checker for v2 of the API, which returns JSON.
 * @since 3.4
 */
class V2TextChecker extends TextChecker {

  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final Pattern COMMA_WHITESPACE_PATTERN = Pattern.compile(",\\s*");
  private static final Logger logger = LoggerFactory.getLogger(V2TextChecker.class);

  private static final Map<String,Float> ruleIdToConfidence = new HashMap<>();

  V2TextChecker(HTTPServerConfig config, boolean internalServer, Queue<Runnable> workQueue, RequestCounter reqCounter) {
    super(config, internalServer, workQueue, reqCounter);
    try {
      loadConfidenceMap();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void loadConfidenceMap() throws IOException {
    if (config.getRuleIdToConfidenceFile() != null) {
      logger.info("Loading confidence map for rules from " + ruleIdToConfidence);
      List<String> lines = Files.readAllLines(Paths.get(config.getRuleIdToConfidenceFile().getAbsolutePath()), UTF_8);
      for (String line : lines) {
        if (line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split(",");
        if (parts.length >= 2) {   // there might be more columns for better debugging, but we don't use them here
          try {
            float confidence = Float.parseFloat(parts[1]);
            ruleIdToConfidence.put(parts[0], confidence);
          } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid confidence float value in " + config.getRuleIdToConfidenceFile() + ", expected 'RULE_ID,float_value[,...]': " + line);
          }
        } else {
          throw new RuntimeException("Invalid line in " + config.getRuleIdToConfidenceFile() + ", expected 'RULE_ID,float_value[,...]': " + line);
        }
      }
    }
  }

  @Override
  protected void setHeaders(HttpExchange httpExchange) {
    setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, config.allowOriginUrl);
  }

  @Override
  protected String getResponse(AnnotatedText text, Language usedLang, DetectedLanguage lang, Language motherTongue, List<CheckResults> matches,
                               List<RuleMatch> hiddenMatches, String incompleteResultsReason, int compactMode, boolean showPremiumHint, JLanguageTool.Mode mode) {
    RuleMatchesAsJsonSerializer serializer = new RuleMatchesAsJsonSerializer(compactMode, usedLang);
    serializer.setRuleIdToConfidenceMap(ruleIdToConfidence);
    return serializer.ruleMatchesToJson2(matches, hiddenMatches, text, CONTEXT_SIZE, lang, incompleteResultsReason,
      showPremiumHint, mode);
  }

  @NotNull
  @Override
  protected List<String> getEnabledRuleIds(Map<String, String> parameters) {
    String enabledParam = parameters.get("enabledRules");
    List<String> enabledRules = new ArrayList<>();
    if (enabledParam != null) {
      enabledRules.addAll(Arrays.asList(enabledParam.split(",")));
    }
    return enabledRules;
  }

  @NotNull
  @Override
  protected List<String> getDisabledRuleIds(Map<String, String> parameters) {
    return getCommaSeparatedStrings("disabledRules", parameters);
  }

  @Override
  protected boolean getLanguageAutoDetect(Map<String, String> parameters) {
    return "auto".equals(parameters.get("language"));
  }

  @Override
  protected void checkParams(Map<String, String> parameters) {
    super.checkParams(parameters);
    if (StringTools.isEmpty(parameters.get("language"))) {
      throw new BadRequestException("Missing 'language' parameter, e.g. 'language=en-US' for American English or 'language=fr' for French");
    }
    if (parameters.get("enabled") != null) {
      throw new BadRequestException("You specified 'enabled' but the parameter is now called 'enabledRules' in v2 of the API");
    }
    if (parameters.get("disabled") != null) {
      throw new BadRequestException("You specified 'disabled' but the parameter is now called 'disabledRules' in v2 of the API");
    }
    if (parameters.get("preferredvariants") != null) {
      throw new BadRequestException("You specified 'preferredvariants' but the parameter is now called 'preferredVariants' (uppercase 'V') in v2 of the API");
    }
    if (parameters.get("autodetect") != null) {
      throw new BadRequestException("You specified 'autodetect' but automatic language detection is now activated with 'language=auto' in v2 of the API");
    }
  }
  
  @Override
  @NotNull
  protected DetectedLanguage getLanguage(String text, Map<String, String> parameters, List<String> preferredVariants,
                                         List<String> noopLangs, List<String> preferredLangs, boolean testMode) {
    String langParam = parameters.get("language");
    boolean forcePreferredLanguages = "true".equals(parameters.get("forcePreferredLanguages"));
    DetectedLanguage detectedLang = detectLanguageOfString(text, null, preferredVariants, noopLangs, preferredLangs, forcePreferredLanguages);
    Language givenLang;
    if (getLanguageAutoDetect(parameters)) {
      givenLang = detectedLang.getDetectedLanguage();
    } else {
      givenLang = parseLanguage(langParam);
    }
    return new DetectedLanguage(givenLang, detectedLang.getDetectedLanguage(), detectedLang.getDetectionConfidence(),
      detectedLang.getDetectionSource());
  }

  @Override
  @NotNull
  protected List<String> getPreferredVariants(Map<String, String> parameters) {
    List<String> preferredVariants;
    if (parameters.get("preferredVariants") != null) {
      preferredVariants = Arrays.asList(COMMA_WHITESPACE_PATTERN.split(parameters.get("preferredVariants")));
      if (!"auto".equals(parameters.get("language")) && (parameters.get("multilingual") == null || parameters.get("multilingual").equals("false"))) {
        throw new BadRequestException("You specified 'preferredVariants' but you didn't specify 'language=auto'");
      }
    } else {
      preferredVariants = Collections.emptyList();
    }
    return preferredVariants;
  }

}
