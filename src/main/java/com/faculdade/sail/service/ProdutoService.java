package com.faculdade.sail.service;

import com.faculdade.sail.model.Produto;
import com.faculdade.sail.model.Registro;
import com.faculdade.sail.model.Usuario;
import com.faculdade.sail.repository.ProdutoRepository;
import com.faculdade.sail.repository.RegistroRepository;
import com.faculdade.sail.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProdutoService {

    @Autowired
    private RegistroRepository registroRepository;

    @Autowired
    private ProdutoRepository repository;

    
    @Autowired
    private UsuarioRepository usuarioRepository;

    
    
    public Produto salvarProduto(Produto produto, Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado!"));
        
        produto.setCriadoPor(usuario); 
        return repository.save(produto);
    }

    
    public List<Produto> listarProdutos() {
        return repository.findAll();
    }

    
    
    public Produto atualizarProduto(Long id, Produto produtoAtualizado, Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado!"));

        return repository.findById(id).map(produto -> {
            produto.setNome(produtoAtualizado.getNome());
            produto.setQuantidade(produtoAtualizado.getQuantidade()); 
            produto.setPreco(produtoAtualizado.getPreco());
            
            produto.setAtualizadoPor(usuario); 
            
            return repository.save(produto);
        }).orElseThrow(() -> new RuntimeException("Produto não encontrado no estoque!"));
    }

    
    public void deletarProduto(Long id) {
        repository.deleteById(id);
    }


    
    public Produto venderProduto(Long id, Integer quantidadeVendida, Long usuarioId) {
        
        
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado!"));

        
        Produto produto = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado!"));

        
        if (produto.getQuantidade() < quantidadeVendida) {
            throw new RuntimeException("Estoque insuficiente para essa venda.");
        }

        
        produto.setQuantidade(produto.getQuantidade() - quantidadeVendida);
        Produto produtoAtualizado = repository.save(produto);

        
        BigDecimal preco = BigDecimal.valueOf(produto.getPreco()); 
        BigDecimal valorTotal = preco.multiply(BigDecimal.valueOf(quantidadeVendida));

        
        Registro novoRegistro = new Registro(produto, quantidadeVendida, valorTotal, usuario);
        registroRepository.save(novoRegistro);

        return produtoAtualizado;
    }
}