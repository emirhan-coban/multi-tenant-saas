package com.saas.backend.operations;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.*;
import java.io.IOException;
import java.util.UUID;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(TraceFilter.class);
    private final com.saas.backend.ops.OpsMetrics metrics;
    public TraceFilter(com.saas.backend.ops.OpsMetrics metrics) {this.metrics=metrics;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        String trace=UUID.randomUUID().toString();
        MDC.put("traceId",trace);res.setHeader("X-Trace-Id",trace);
        long start=System.nanoTime();
        try { chain.doFilter(req,res); }
        finally {
            log.info("HTTP method={} status={} durationMs={}",req.getMethod(),res.getStatus(),(System.nanoTime()-start)/1000000);
            if(res.getStatus()>=500) metrics.serverError(req.getMethod());
            MDC.remove("traceId"); MDC.remove("tenantId");
        }
    }
}
