package in.gov.sarthi.result.config;

import in.gov.sarthi.result.security.AdminAuthInterceptor;
import in.gov.sarthi.result.security.PartnerAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final PartnerAuthInterceptor partnerAuthInterceptor;

    public WebConfig(AdminAuthInterceptor adminAuthInterceptor, PartnerAuthInterceptor partnerAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.partnerAuthInterceptor = partnerAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/grievances/admin/**", "/api/applications/admin/**");
        registry.addInterceptor(partnerAuthInterceptor)
                .addPathPatterns("/api/partner/**");
    }
}
