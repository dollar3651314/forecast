package com.forecast.app.growth.board;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 数据页兼等候页的数据（forecast S16，MF-8、MF-17）：{@code GET /api/public/board}，不用登录（{@code SessionFilter.PUBLIC}）。见 {@link BoardService}。 */
@RestController
class BoardController {

  private final BoardService board;

  BoardController(BoardService board) {
    this.board = board;
  }

  @GetMapping("/api/public/board")
  ResponseEntity<BoardService.Board> board() {
    return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(board.board());
  }
}
