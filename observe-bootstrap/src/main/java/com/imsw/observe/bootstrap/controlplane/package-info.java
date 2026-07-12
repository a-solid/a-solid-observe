/**
 * Controlplane 进程装配占位。
 *
 * <p>一期 controlplane 与 worker 同 JVM（main profile 路由），装配复用 worker/config。
 * 未来 controlplane 独立进程时，在此包加 @Configuration 装配 controlplane 专用 bean。
 */
package com.imsw.observe.bootstrap.controlplane;
