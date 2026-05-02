package com.faculdade.sail.controller;

import com.faculdade.sail.model.Produto;
import com.faculdade.sail.service.ProdutoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/produtos")
@CrossOrigin("*") 
public class ProdutoController {

    @Autowired
    private ProdutoService service;

    
    @PostMapping
    public Produto adicionar(@RequestBody Produto produto) {
        return service.salvarProduto(produto);
    }

    
    @GetMapping
    public List<Produto> listar() {
        return service.listarProdutos();
    }

    
    @PutMapping("/{id}")
    public ResponseEntity<?> atualizar(@PathVariable Long id, @RequestBody Produto produtoAtualizado) {
        try {
            Produto produto = service.atualizarProduto(id, produtoAtualizado);
            return ResponseEntity.ok(produto);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletar(@PathVariable Long id) {
        try {
            service.deletarProduto(id);
            return ResponseEntity.ok("Produto removido do estoque com sucesso!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Erro ao remover produto.");
        }
    }
}