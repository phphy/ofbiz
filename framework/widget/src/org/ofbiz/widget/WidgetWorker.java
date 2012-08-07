/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.widget;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilURL;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.collections.FlexibleMapAccessor;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.entity.Delegator;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.webapp.control.ConfigXMLReader;
import org.ofbiz.webapp.control.RequestHandler;
import org.ofbiz.webapp.taglib.ContentUrlTag;
import org.ofbiz.widget.ModelWidget.ShowPortletItemData;
import org.ofbiz.widget.ModelWidget.ShowPortletLinkData;
import org.ofbiz.widget.form.ModelForm;
import org.ofbiz.widget.form.ModelFormField;
import org.w3c.dom.Element;

public class WidgetWorker {

    public static final String module = WidgetWorker.class.getName();

    public WidgetWorker () {}

    public static void buildHyperlinkUrl(Appendable externalWriter, String target, String targetType, Map<String, String> parameterMap,
            String prefix, boolean fullPath, boolean secure, boolean encode, HttpServletRequest request, HttpServletResponse response, Map<String, Object> context) throws IOException {
        String localRequestName = UtilHttp.encodeAmpersands(target);
        Appendable localWriter = new StringWriter();

        if ("intra-app".equals(targetType)) {
            if (request != null && response != null) {
                ServletContext servletContext = request.getSession().getServletContext();
                RequestHandler rh = (RequestHandler) servletContext.getAttribute("_REQUEST_HANDLER_");
                externalWriter.append(rh.makeLink(request, response, "/" + localRequestName, fullPath, secure, encode));
            } else if (prefix != null) {
                externalWriter.append(prefix);
                externalWriter.append(localRequestName);
            } else {
                externalWriter.append(localRequestName);
            }
        } else if ("inter-app".equals(targetType)) {
            String fullTarget = localRequestName;
            localWriter.append(fullTarget);
            String externalLoginKey = (String) request.getAttribute("externalLoginKey");
            if (UtilValidate.isNotEmpty(externalLoginKey)) {
                if (fullTarget.indexOf('?') == -1) {
                    localWriter.append('?');
                } else {
                    localWriter.append("&amp;");
                }
                localWriter.append("externalLoginKey=");
                localWriter.append(externalLoginKey);
            }
        } else if ("content".equals(targetType)) {
            appendContentUrl(localWriter, localRequestName, request);
        } else if ("plain".equals(targetType)) {
            localWriter.append(localRequestName);
        } else {
            localWriter.append(localRequestName);
        }

        if (UtilValidate.isNotEmpty(parameterMap)) {
            String localUrl = localWriter.toString();
            externalWriter.append(localUrl);
            boolean needsAmp = true;
            if (localUrl.indexOf('?') == -1) {
                externalWriter.append('?');
                needsAmp = false;
            }

            for (Map.Entry<String, String> parameter: parameterMap.entrySet()) {
                String parameterValue = null;
                if (parameter.getValue() instanceof String) {
                    parameterValue = parameter.getValue();
                } else {
                    Object parameterObject = parameter.getValue();

                    // skip null values
                    if (parameterObject == null) continue;

                    if (parameterObject instanceof String[]) {
                        // it's probably a String[], just get the first value
                        String[] parameterArray = (String[]) parameterObject;
                        parameterValue = parameterArray[0];
                        Debug.logInfo("Found String array value for parameter [" + parameter.getKey() + "], using first value: " + parameterValue, module);
                    } else {
                        // not a String, and not a String[], just use toString
                        parameterValue = parameterObject.toString();
                    }
                }

                if (needsAmp) {
                    externalWriter.append("&amp;");
                } else {
                    needsAmp = true;
                }
                externalWriter.append(parameter.getKey());
                externalWriter.append('=');
                StringUtil.SimpleEncoder simpleEncoder = (StringUtil.SimpleEncoder) context.get("simpleEncoder");
                if (simpleEncoder != null) {
                    externalWriter.append(simpleEncoder.encode(parameterValue));
                } else {
                    externalWriter.append(parameterValue);
                }
            }
        } else {
            externalWriter.append(localWriter.toString());
        }
    }

    //#Bam# portletWidget
    public static void buildShowPortletUrl(Appendable externalWriter, String target, Map<String, String> parameterMap,
            String prefix, boolean fullPath, boolean secure, boolean encode, HttpServletRequest request, HttpServletResponse response, Map<String, Object> context) throws IOException {
    }
    //#Eam# portletWidget

    public static void appendContentUrl(Appendable writer, String location, HttpServletRequest request) throws IOException {
        StringBuilder buffer = new StringBuilder();
        ContentUrlTag.appendContentPrefix(request, buffer);
        writer.append(buffer.toString());
        writer.append(location);
    }
    public static void makeHyperlinkByType(Appendable writer, String linkType, String linkStyle, String targetType, String target,
            Map<String, String> parameterMap, String description, String targetWindow, String confirmation, ModelFormField modelFormField,
            HttpServletRequest request, HttpServletResponse response, Map<String, Object> context) throws IOException {
        String realLinkType = WidgetWorker.determineAutoLinkType(linkType, target, targetType, request);
        if ("hidden-form".equals(realLinkType)) {
            if (modelFormField != null && "multi".equals(modelFormField.getModelForm().getType())) {
                WidgetWorker.makeHiddenFormLinkAnchor(writer, linkStyle, description, confirmation, modelFormField, request, response, context);

                // this is a bit trickier, since we can't do a nested form we'll have to put the link to submit the form in place, but put the actual form def elsewhere, ie after the big form is closed
                Map<String, Object> wholeFormContext = UtilGenerics.checkMap(context.get("wholeFormContext"));
                Appendable postMultiFormWriter = wholeFormContext != null ? (Appendable) wholeFormContext.get("postMultiFormWriter") : null;
                if (postMultiFormWriter == null) {
                    postMultiFormWriter = new StringWriter();
                    wholeFormContext.put("postMultiFormWriter", postMultiFormWriter);
                }
                WidgetWorker.makeHiddenFormLinkForm(postMultiFormWriter, target, targetType, targetWindow, parameterMap, modelFormField, request, response, context);
            } else {
                WidgetWorker.makeHiddenFormLinkForm(writer, target, targetType, targetWindow, parameterMap, modelFormField, request, response, context);
                WidgetWorker.makeHiddenFormLinkAnchor(writer, linkStyle, description, confirmation, modelFormField, request, response, context);
            }
        } else {
            WidgetWorker.makeHyperlinkString(writer, linkStyle, targetType, target, parameterMap, description, confirmation, modelFormField, request, response, context, targetWindow);
        }

    }
    public static void makeHyperlinkString(Appendable writer, String linkStyle, String targetType, String target, Map<String, String> parameterMap,
            String description, String confirmation, ModelFormField modelFormField, HttpServletRequest request, HttpServletResponse response, Map<String, Object> context, String targetWindow)
            throws IOException {
        if (UtilValidate.isNotEmpty(description) || UtilValidate.isNotEmpty(request.getAttribute("image"))) {
            writer.append("<a");

            if (UtilValidate.isNotEmpty(linkStyle)) {
                writer.append(" class=\"");
                writer.append(linkStyle);
                writer.append("\"");
            }

            writer.append(" href=\"");

            buildHyperlinkUrl(writer, target, targetType, parameterMap, null, false, false, true, request, response, context);

            writer.append("\"");

            if (UtilValidate.isNotEmpty(targetWindow)) {
                writer.append(" target=\"");
                writer.append(targetWindow);
                writer.append("\"");
            }

            if (UtilValidate.isNotEmpty(modelFormField.getEvent()) && UtilValidate.isNotEmpty(modelFormField.getAction(context))) {
                writer.append(" ");
                writer.append(modelFormField.getEvent());
                writer.append("=\"");
                writer.append(modelFormField.getAction(context));
                writer.append('"');
            }
            if (UtilValidate.isNotEmpty(confirmation)){
                writer.append(" onclick=\"return confirm('");
                writer.append(confirmation);
                writer.append("')\"");
            }
            writer.append('>');

            if (UtilValidate.isNotEmpty(request.getAttribute("image"))) {
                writer.append("<img src=\"");
                writer.append(request.getAttribute("image").toString());
                writer.append("\"/>");
            }

            writer.append(description);
            writer.append("</a>");
        }
    }

    public static void makeHiddenFormLinkAnchor(Appendable writer, String linkStyle, String description, String confirmation, ModelFormField modelFormField, HttpServletRequest request, HttpServletResponse response, Map<String, Object> context) throws IOException {
        if (UtilValidate.isNotEmpty(description) || UtilValidate.isNotEmpty(request.getAttribute("image"))) {
            writer.append("<a");

            if (UtilValidate.isNotEmpty(linkStyle)) {
                writer.append(" class=\"");
                writer.append(linkStyle);
                writer.append("\"");
            }

            writer.append(" href=\"javascript:document.");
            writer.append(makeLinkHiddenFormName(context, modelFormField));
            writer.append(".submit()\"");

            if (UtilValidate.isNotEmpty(modelFormField.getEvent()) && UtilValidate.isNotEmpty(modelFormField.getAction(context))) {
                writer.append(" ");
                writer.append(modelFormField.getEvent());
                writer.append("=\"");
                writer.append(modelFormField.getAction(context));
                writer.append('"');
            }

            if (UtilValidate.isNotEmpty(confirmation)){
                writer.append(" onclick=\"return confirm('");
                writer.append(confirmation);
                writer.append("')\"");
            }

            writer.append('>');

            if (UtilValidate.isNotEmpty(request.getAttribute("image"))) {
                writer.append("<img src=\"");
                writer.append(request.getAttribute("image").toString());
                writer.append("\"/>");
            }

            writer.append(description);
            writer.append("</a>");
        }
    }

    public static void makeHiddenFormLinkForm(Appendable writer, String target, String targetType, String targetWindow, Map<String, String> parameterMap, ModelFormField modelFormField, HttpServletRequest request, HttpServletResponse response, Map<String, Object> context) throws IOException {
        writer.append("<form method=\"post\"");
        writer.append(" action=\"");
        // note that this passes null for the parameterList on purpose so they won't be put into the URL
        WidgetWorker.buildHyperlinkUrl(writer, target, targetType, null, null, false, false, true, request, response, context);
        writer.append("\"");

        if (UtilValidate.isNotEmpty(targetWindow)) {
            writer.append(" target=\"");
            writer.append(targetWindow);
            writer.append("\"");
        }

        writer.append(" onsubmit=\"javascript:submitFormDisableSubmits(this)\"");

        writer.append(" name=\"");
        writer.append(makeLinkHiddenFormName(context, modelFormField));
        writer.append("\">");

        for (Map.Entry<String, String> parameter: parameterMap.entrySet()) {
            if (parameter.getValue() != null) {
                writer.append("<input name=\"");
                writer.append(parameter.getKey());
                writer.append("\" value=\"");
                writer.append(parameter.getValue());
                writer.append("\" type=\"hidden\"/>");
            }
        }

        writer.append("</form>");
    }

    public static String makeLinkHiddenFormName(Map<String, Object> context, ModelFormField modelFormField) {
        ModelForm modelForm = modelFormField.getModelForm();
        Integer itemIndex = (Integer) context.get("itemIndex");
        String iterateId = "";
        String formUniqueId = "";
        String formName = (String) context.get("formName");
        if (UtilValidate.isEmpty(formName)) {
            formName = modelForm.getName();
        }
        if (UtilValidate.isNotEmpty(context.get("iterateId"))) {
            iterateId = (String) context.get("iterateId");
        }
        if (UtilValidate.isNotEmpty(context.get("formUniqueId"))) {
            formUniqueId = (String) context.get("formUniqueId");
        }
        if (itemIndex != null) {
            return formName + modelForm.getItemIndexSeparator() + itemIndex.intValue() + iterateId + formUniqueId + modelForm.getItemIndexSeparator() + modelFormField.getName();
        } else {
            return formName + modelForm.getItemIndexSeparator() + modelFormField.getName();
        }
    }

    public static class Parameter {
        protected String name;
        protected FlexibleStringExpander value;
        protected FlexibleStringExpander sendIfEmpty; //#Eam# portletWidget
        protected FlexibleMapAccessor<Object> fromField;

        public Parameter(Element element) {
            this.name = element.getAttribute("param-name");
            this.value = UtilValidate.isNotEmpty(element.getAttribute("value")) ? FlexibleStringExpander.getInstance(element.getAttribute("value")) : null;
            this.fromField = UtilValidate.isNotEmpty(element.getAttribute("from-field")) ? FlexibleMapAccessor.getInstance(element.getAttribute("from-field")) : null;
            this.sendIfEmpty = UtilValidate.isNotEmpty(element.getAttribute("send-if-empty")) ? FlexibleStringExpander.getInstance(element.getAttribute("send-if-empty")) : null; //#Eam# portletWidget
        }

        public Parameter(String paramName, String paramValue, boolean isField) {
            this.name = paramName;
            if (isField) {
                this.fromField = FlexibleMapAccessor.getInstance(paramValue);
            } else {
                this.value = FlexibleStringExpander.getInstance(paramValue);
            }
        }

        public String getName() {
            return name;
        }

        public String getValue(Map<String, Object> context) {
            if (this.value != null) {
                try {
                    return URLEncoder.encode(this.value.expandString(context), Charset.forName("UTF-8").displayName());
                } catch (UnsupportedEncodingException e) {
                    Debug.logError(e, module);
                    return this.value.expandString(context);
                }
            }

            Object retVal = null;
            if (this.fromField != null && this.fromField.get(context) != null) {
                retVal = this.fromField.get(context);
            } else {
                retVal = context.get(this.name);
            }

            if (retVal != null) {
                TimeZone timeZone = (TimeZone) context.get("timeZone");
                if (timeZone == null) timeZone = TimeZone.getDefault();

                String returnValue = null;
                // format string based on the user's time zone (not locale because these are parameters)
                if (retVal instanceof Double || retVal instanceof Float || retVal instanceof BigDecimal) {
                    returnValue = retVal.toString();
                } else if (retVal instanceof java.sql.Date) {
                    DateFormat df = UtilDateTime.toDateFormat(UtilDateTime.DATE_FORMAT, timeZone, null);
                    returnValue = df.format((java.util.Date) retVal);
                } else if (retVal instanceof java.sql.Time) {
                    DateFormat df = UtilDateTime.toTimeFormat(UtilDateTime.TIME_FORMAT, timeZone, null);
                    returnValue = df.format((java.util.Date) retVal);
                } else if (retVal instanceof java.sql.Timestamp) {
                    DateFormat df = UtilDateTime.toDateTimeFormat(UtilDateTime.DATE_TIME_FORMAT, timeZone, null);
                    returnValue = df.format((java.util.Date) retVal);
                } else if (retVal instanceof java.util.Date) {
                    DateFormat df = UtilDateTime.toDateTimeFormat("EEE MMM dd hh:mm:ss z yyyy", timeZone, null);
                    returnValue = df.format((java.util.Date) retVal);
                } else {
                    try {
                        returnValue = URLEncoder.encode(retVal.toString(), Charset.forName("UTF-8").displayName());
                    } catch (UnsupportedEncodingException e) {
                        Debug.logError(e, module);
                    }
                }
                return returnValue;
            } else {
                return null;
            }
        }
        //#Bam# portletWidget
        public boolean sendIfEmpty(Map<String, Object> context) {
            if (this.sendIfEmpty != null)
              return "true".equals(this.sendIfEmpty.expandString(context));
            else return false;
        }
        //#Eam# portletWidget
    }

    public static String determineAutoLinkType(String linkType, String target, String targetType, HttpServletRequest request) {
        if ("auto".equals(linkType)) {
            if ("intra-app".equals(targetType)) {
                String requestUri = (target.indexOf('?') > -1) ? target.substring(0, target.indexOf('?')) : target;
                ServletContext servletContext = request.getSession().getServletContext();
                RequestHandler rh = (RequestHandler) servletContext.getAttribute("_REQUEST_HANDLER_");
                ConfigXMLReader.RequestMap requestMap = rh.getControllerConfig().getRequestMapMap().get(requestUri);
                if (requestMap != null && requestMap.event != null) {
                    return "hidden-form";
                } else {
                    return "anchor";
                }
            } else {
                return "anchor";
            }
        } else {
            return linkType;
        }
    }

    /** Returns the script location based on a script combined name:
     * <code>location#methodName</code>.
     *
     * @param combinedName The combined location/method name
     * @return The script location
     */
    public static String getScriptLocation(String combinedName) {
        int pos = combinedName.lastIndexOf("#");
        if (pos == -1) {
            return combinedName;
        }
        return combinedName.substring(0, pos);
    }

    /** Returns the script method name based on a script combined name:
     * <code>location#methodName</code>. Returns <code>null</code> if
     * no method name is found.
     *
     * @param combinedName The combined location/method name
     * @return The method name or <code>null</code>
     */
    public static String getScriptMethodName(String combinedName) {
        int pos = combinedName.lastIndexOf("#");
        if (pos == -1) {
            return null;
        }
        return combinedName.substring(pos + 1);
    }

    public static int getPaginatorNumber(Map<String, Object> context) {
        int paginator_number = 0;
        Map<String, Object> globalCtx = UtilGenerics.checkMap(context.get("globalContext"));
        if (globalCtx != null) {
            Integer paginateNumberInt= (Integer)globalCtx.get("PAGINATOR_NUMBER");
            if (paginateNumberInt == null) {
                paginateNumberInt = Integer.valueOf(0);
                globalCtx.put("PAGINATOR_NUMBER", paginateNumberInt);
            }
            paginator_number = paginateNumberInt.intValue();
        }
        return paginator_number;
    }

    public static void incrementPaginatorNumber(Map<String, Object> context) {
        Map<String, Object> globalCtx = UtilGenerics.checkMap(context.get("globalContext"));
        if (globalCtx != null) {
            Boolean NO_PAGINATOR = (Boolean) globalCtx.get("NO_PAGINATOR");
            if (UtilValidate.isNotEmpty(NO_PAGINATOR)) {
                globalCtx.remove("NO_PAGINATOR");
            } else {
                Integer paginateNumberInt = Integer.valueOf(getPaginatorNumber(context) + 1);
                globalCtx.put("PAGINATOR_NUMBER", paginateNumberInt);
            }
        }
    }

    public static LocalDispatcher getDispatcher(Map<String, Object> context) {
        LocalDispatcher dispatcher = (LocalDispatcher) context.get("dispatcher");
        return dispatcher;
    }

    public static Delegator getDelegator(Map<String, Object> context) {
        Delegator delegator = (Delegator) context.get("delegator");
        return delegator;
    }
    //#Bam# portletWidget
    /**
     * Prepare data for Form and Menu show-portlet
     */
    public static ShowPortletLinkData prepareShowPortletLinkData(ModelWidget.ShowPortletLink showPortletLink, Map<String, Object> context) {
        ShowPortletLinkData splData = new ShowPortletLinkData();
        splData.imgSrc = showPortletLink.getImage(context);
        if (UtilValidate.isEmpty(splData.imgSrc)) {
            splData.imgSrc  = "";
        }
        splData.imgTitle = showPortletLink.getImageTitle(context);
        if (UtilValidate.isEmpty(splData.imgTitle)) {
            splData.imgTitle = "";
        }
        splData.alt = showPortletLink.getAlternate(context);
        if (UtilValidate.isEmpty(splData.alt)) {
            splData.alt = "";
        }
        splData.description = showPortletLink.getDescription(context);
        if(UtilValidate.isEmpty(splData.description)) {
            splData.description = "";
        }
        return splData;
    }
    public static ShowPortletItemData prepareShowPortletItemsData(ModelWidget.ShowPortletItem showPortletItem, Map<String, Object> context) {
        ShowPortletItemData spiData = new ShowPortletItemData();
        spiData.portalPageId = showPortletItem.getPortalPageId(context);
        spiData.portletId = showPortletItem.getPortletId(context);
        spiData.portletSeqId = showPortletItem.getPortletSeqId(context);
        if (UtilValidate.isEmpty(spiData.portalPageId) && context.containsKey("portalPageId")) {
            spiData.portalPageId = (String) context.get("portalPageId");
        }
        // portletID is mandatory in show-portlet so, if value is null it's a choice
        //if (UtilValidate.isEmpty(portletId) && context.containsKey("portalPortletId"))
        //    portletId = (String) context.get("portalPortletId");
        if (UtilValidate.isEmpty(spiData.portletSeqId) && context.containsKey("portletSeqId")) {
            spiData.portletSeqId = (String) context.get("portletSeqId");
        }
        spiData.areaId = showPortletItem.getAreaId(context);
        if (UtilValidate.isEmpty(spiData.areaId)) {
            if (UtilValidate.isNotEmpty(spiData.portalPageId) && UtilValidate.isNotEmpty(spiData.portletId) && UtilValidate.isNotEmpty(spiData.portletSeqId)) {
                spiData.areaId = "PP_" + spiData.portalPageId + spiData.portletId + spiData.portletSeqId;
            }
            else {
                //Debug.logWarning("The form [" + modelFormField.getModelForm().getFormLocation() + "#" + modelFormField.getModelForm().getName() +"] has a show-portlet field that should define a target-area  or must have target-page-id, target-portlet-id and target-seq_id attributes", module);
            }
        }
        spiData.target = "showPortlet";
        if (UtilValidate.isNotEmpty(showPortletItem.getTarget(context))) {
            spiData.target = showPortletItem.getTarget(context);
        }

        StringBuilder params = new StringBuilder();
        Map<String, String> parameters = showPortletItem.getParameterMap(context);
        for (String key : parameters.keySet()) {
            WidgetWorker.addToParams(params, key, parameters.get(key));
        }

        if (UtilValidate.isNotEmpty(spiData.portalPageId)) {
            WidgetWorker.addToParams(params, "portalPageId", spiData.portalPageId);
        } else {
            WidgetWorker.addToParamsIfInContext(params, context, "portalPageId", parameters);
        }

        if (UtilValidate.isNotEmpty(spiData.portletId)) {
            WidgetWorker.addToParams(params, "portalPortletId", spiData.portletId);
        }

        if (UtilValidate.isNotEmpty(spiData.portletSeqId)) {
            WidgetWorker.addToParams(params, "portletSeqId", spiData.portletSeqId);
        } else {
            WidgetWorker.addToParamsIfInContext(params, context, "portletSeqId", parameters);
        }

        WidgetWorker.addToParamsIfInContext(params, context, "areaId", parameters);
        WidgetWorker.addToParamsIfInContext(params, context, "idDescription", parameters);
        spiData.params = params;
        return spiData;
    }

    /**
     * if context.get(key) not empty or context.get(parameters.key) not empty and not already in parameters add key=keyValue in params
     * @param params
     * @param context
     * @param key
     * @param parameters
     */
    public static void addToParamsIfInContext(StringBuilder params, Map<String, Object> context, String key, Map<String, String> parameters) {
        if (parameters.containsKey(key)) return;

        String paramValue = (String) context.get(key);
        if (UtilValidate.isEmpty(paramValue)) {
            Map<String, Object> contextParameters = UtilGenerics.checkMap(context.get("parameters"));
            paramValue = (String) contextParameters.get(key);
        }
        if (UtilValidate.isNotEmpty(paramValue)) {
            if ("idDescription".equals(key)) {
                addToParams(params, key, UtilURL.removeBadCharForUrl(paramValue));
            }
            else {
                addToParams(params, key, paramValue);
            }
        }
    }
    public static void addToParams(StringBuilder params, String key, String oneParam) {
        if (UtilValidate.isNotEmpty(params)) {
            params.append("&");
        }
        params.append(key).append("=").append(oneParam);
    }
    //#Eam# portletWidget
}
