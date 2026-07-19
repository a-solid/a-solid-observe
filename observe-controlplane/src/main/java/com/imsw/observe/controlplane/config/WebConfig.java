package com.imsw.observe.controlplane.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 前端静态资源映射（SPA 服务）。
 *
 * <p>前端构建产物（dist）放在 {@code observe-controlplane/src/main/resources/static/} 下，bootstrap
 * 打 fat jar 时打进 classpath。本配置做两件事：
 * <ul>
 *   <li>服务静态资源（CSS/JS/图片等）——Spring Boot 默认就映射 {@code /static}，这里显式声明带 cache。</li>
 *   <li><b>SPA history fallback</b>：浏览器直接访问前端路由（如 {@code /alerts/123}）时，没有对应静态文件，
 *       转发到 {@code index.html} 让前端路由器接管——避免刷新 404。</li>
 * </ul>
 *
 * <p>{@code /api/**} 走后端 REST，不被本映射拦截（API controller 先匹配）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaIndexFallbackResolver());
    }

    /**
     * SPA fallback：请求对应静态资源不存在时（且非 API、非扩展名资源），转发到 index.html。
     */
    private static final class SpaIndexFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(final String resourcePath, final Resource location) throws IOException {
            Resource resource = location.createRelative(resourcePath);
            // 资源存在且可读 → 直接返回（静态文件）。
            if (resource != null && resource.isReadable()) {
                return resource;
            }
            // 不存在 → 若是前端路由（无文件扩展名），fallback 到 index.html。
            // 有扩展名的 404（如 /foo.js 缺失）不 fallback，让浏览器看到真实 404。
            if (resourcePath != null && !resourcePath.contains(".")) {
                Resource index = new ClassPathResource("/static/index.html");
                if (index.isReadable()) {
                    return index;
                }
            }
            return null;
        }
    }
}
