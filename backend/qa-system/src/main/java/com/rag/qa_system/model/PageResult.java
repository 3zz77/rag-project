package com.rag.qa_system.model;

import java.util.List;

/**
 * 分页结果
 */
public class PageResult<T> {

    private List<T> list;
    private long total;
    private int page;
    private int pageSize;
    private int totalPages;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, int page, int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = (int) Math.ceil((double) total / pageSize);
    }

    public List<T> getList() { return list; }
    public long getTotal() { return total; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
    public int getTotalPages() { return totalPages; }

    public void setList(List<T> list) { this.list = list; }
    public void setTotal(long total) { this.total = total; }
    public void setPage(int page) { this.page = page; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
}
