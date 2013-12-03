package io.github.ibuildthecloud.gdapi.servlet;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.parser.ApiRequestParser;
import io.github.ibuildthecloud.gdapi.server.model.RequestServletContext;
import io.github.ibuildthecloud.gdapi.util.ExceptionUtils;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import javax.inject.Inject;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiRequestFilterDelegate  {

    public static final String SCHEMAS_HEADER = "X-API-Schemas";

    private static final Logger log = LoggerFactory.getLogger(ApiRequestFilterDelegate.class);

    ApiRequestParser parser;
    List<ApiRequestHandler> handlers;
    boolean throwErrors = false;
    String version = "v1";
    SchemaFactory schemaFactory;

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException {

        if ( ! (request instanceof HttpServletRequest) || ! (response instanceof HttpServletResponse) ) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpServletResponse httpResponse = (HttpServletResponse)response;

        ApiRequest apiRequest = new ApiRequest(version, new RequestServletContext(httpRequest, httpResponse, chain));

        try {
            ApiContext context = ApiContext.newContext();
            context.setApiRequest(apiRequest);
            context.setSchemaFactory(schemaFactory);

            if ( ! parser.parse(apiRequest) ) {
                chain.doFilter(httpRequest, httpResponse);
                return;
            }

            URL schemaUrl = ApiContext.getUrlBuilder().resourceCollection(Schema.class);
            if ( schemaUrl != null ) {
                httpResponse.setHeader(SCHEMAS_HEADER, schemaUrl.toExternalForm());
            }

            for ( ApiRequestHandler handler : handlers ) {
                handler.handle(apiRequest);
            }
        } catch ( Throwable t ) {
            boolean handled = false;
            for ( ApiRequestHandler handler : handlers ) {
                handled |= handler.handleException(apiRequest, t);
            }
            if ( ! handled ) {
                log.error("Unhandled exception in API for request [{}]", apiRequest, t);
                if ( throwErrors ) {
                    ExceptionUtils.rethrowRuntime(t);
                    ExceptionUtils.rethrow(t, IOException.class);
                    ExceptionUtils.rethrow(t, ServletException.class);
                    throw new ServletException(t);
                } else {
                    if ( ! apiRequest.isCommited() ) {
                        apiRequest.setResponseCode(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    }
                }
            }
        } finally {
            apiRequest.commit();
            ApiContext.remove();
        }
    }

    public ApiRequestParser getParser() {
        return parser;
    }

    @Inject
    public void setParser(ApiRequestParser parser) {
        this.parser = parser;
    }

    public boolean isThrowErrors() {
        return throwErrors;
    }

    public void setThrowErrors(boolean throwErrors) {
        this.throwErrors = throwErrors;
    }

    public List<ApiRequestHandler> getHandlers() {
        return handlers;
    }

    @Inject
    public void setHandlers(List<ApiRequestHandler> handlers) {
        this.handlers = handlers;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public SchemaFactory getSchemaFactory() {
        return schemaFactory;
    }

    @Inject
    public void setSchemaFactory(SchemaFactory schemaFactory) {
        this.schemaFactory = schemaFactory;
    }

}
