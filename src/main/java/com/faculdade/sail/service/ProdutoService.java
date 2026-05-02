package com.faculdade.sail.service;

import com.faculdade.sail.model.Produto;
import com.faculdade.sail.repository.ProdutoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProdutoService {

    @Autowired
    private ProdutoRepository repository;

    
    public Produto salvarProduto(Produto produto) {
        return repository.save(produto);
    }

    
    public List<Produto> listarProdutos() {
        return repository.findAll();
    }

    
    public Produto atualizarProduto(Long id, Produto produtoAtualizado) {
        return repository.findById(id).map(produto -> {
            produto.setNome(produtoAtualizado.getNome());
            produto.setQuantidade(produtoAtualizado.getQuantidade()); 
            produto.setPreco(produtoAtualizado.getPreco());
            return repository.save(produto);
        }).orElseThrow(() -> new RuntimeException("Produto não encontrado no estoque!"));
    }

    
    public void deletarProduto(Long id) {
        repository.deleteById(id);
    }
}