package com.vishwesh.repolens.api;

import com.vishwesh.repolens.api.dto.CreateRepoRequest;
import com.vishwesh.repolens.api.dto.CreateRepoResponse;
import com.vishwesh.repolens.api.dto.RepositoryResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repos")
public class RepoController {

  private final RepoService repoService;

  public RepoController(RepoService repoService) {
    this.repoService = repoService;
  }

  @PostMapping
  public ResponseEntity<CreateRepoResponse> create(@Valid @RequestBody CreateRepoRequest request) {
    RepoService.CreatedRepo created = repoService.create(request.url());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/repos/" + created.repoId()))
        .body(new CreateRepoResponse(created.repoId(), created.jobId(), "PENDING"));
  }

  @GetMapping
  public List<RepositoryResponse> list() {
    return repoService.list();
  }

  @GetMapping("/{id}")
  public RepositoryResponse get(@PathVariable long id) {
    return repoService.find(id);
  }

  @PostMapping("/{id}/reindex")
  public ResponseEntity<CreateRepoResponse> reindex(@PathVariable long id) {
    RepoService.CreatedRepo created = repoService.reindex(id);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/repos/" + id))
        .body(new CreateRepoResponse(created.repoId(), created.jobId(), "PENDING"));
  }
}
