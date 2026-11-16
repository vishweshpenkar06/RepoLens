package com.vishwesh.repoprism.api;

import com.vishwesh.repoprism.retrieve.SearchResult;
import com.vishwesh.repoprism.retrieve.SearchService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repos")
public class SearchController {

  private final SearchService searchService;

  public SearchController(SearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping("/{id}/search")
  public List<SearchResult> search(
      @PathVariable long id,
      @RequestParam(name = "q", required = false) String query,
      @RequestParam(name = "k", defaultValue = "6") int limit) {
    return searchService.search(id, query, limit);
  }
}
