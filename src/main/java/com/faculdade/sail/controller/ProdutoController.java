package com.faculdade.sail.controller;

import com.faculdade.sail.model.Produto;
import com.faculdade.sail.model.Registro;
import com.faculdade.sail.service.ProdutoService;
import com.faculdade.sail.repository.RegistroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/produtos")
@CrossOrigin(origins = "*")
public class ProdutoController {

    @Autowired
    private ProdutoService service;

    @Autowired
    private RegistroRepository registroRepository;

    @PostMapping
    public Produto adicionar(
            @RequestBody Produto produto,
            @RequestHeader("usuarioId") Long usuarioId) {
        Produto salvo = service.salvarProduto(produto, usuarioId);
        Registro registroCadastro = new Registro(salvo, salvo.getQuantidade(), BigDecimal.ZERO, salvo.getCriadoPor(), "CADASTRO");
        registroRepository.save(registroCadastro);
        return salvo;
    }

    @PostMapping("/{id}/vender")
    public ResponseEntity<Produto> vender(
            @PathVariable Long id,
            @RequestParam Integer quantidade,
            @RequestHeader("usuarioId") Long usuarioId) {
        try {
            Produto produtoVendido = service.venderProduto(id, quantidade, usuarioId);
            return ResponseEntity.ok(produtoVendido);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> atualizar(
            @PathVariable Long id,
            @RequestBody Produto produtoAtualizado,
            @RequestHeader("usuarioId") Long usuarioId) {
        try {
            Produto produto = service.atualizarProduto(id, produtoAtualizado, usuarioId);
            return ResponseEntity.ok(produto);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    public List<Produto> listar() {
        return service.listarProdutos();
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