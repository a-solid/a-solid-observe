package com.imsw.observe.controlplane.interfaces.web;

/** 分页元信息（{@code {page,size,total}}），仅分页列表响应携带。 */
public record Page(int page, int size, long total) {}
