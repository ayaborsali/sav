package tn.higer.sav_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Les trois fichiers qui décident de l'installation et de la mise à jour de la
 * PWA ne doivent jamais être servis depuis un cache : sinon un navigateur peut
 * garder un `index.html` qui pointe vers des scripts disparus, ou un
 * `ngsw.json` qui n'annonce jamais la nouvelle version.
 */
@Configuration
public class PwaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html", "/ngsw.json", "/ngsw-worker.js")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
