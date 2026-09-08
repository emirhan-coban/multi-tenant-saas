package com.saas.backend.operations;
import com.saas.backend.config.AuthenticatedUser;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
public class RateLimitFilter extends OncePerRequestFilter {
    private final TenantRateLimiter limiter;
    private final OperationsService operations;
    public RateLimitFilter(TenantRateLimiter limiter,OperationsService operations) {this.limiter=limiter;this.operations=operations;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(!"OPTIONS".equals(request.getMethod()) && auth!=null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            try {
                var probe=limiter.consume(u.tenantId(),"PRO".equals(operations.plan(u.tenantId())));
                response.setHeader("X-RateLimit-Remaining",Long.toString(probe.getRemainingTokens()));
                if(!probe.isConsumed()) {
                    long retry=Math.max(1,(probe.getNanosToWaitForRefill()+999999999L)/1000000000L);
                    reject(response,429,"RATE_LIMIT_EXCEEDED","Çok fazla istek gönderdiniz. Lütfen biraz bekleyin.",retry); return;
                }
            } catch(RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rate limit dependency unavailable");
                reject(response,503,"RATE_LIMIT_UNAVAILABLE","İstek denetimi geçici olarak kullanılamıyor. Tekrar deneyin.",5);return;
            }
        }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String code,String message,long retry) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After",Long.toString(retry));
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
}
