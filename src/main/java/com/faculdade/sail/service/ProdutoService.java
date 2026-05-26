package com.faculdade.sail.service;


import com.faculdade.sail.model.Registro;
import com.faculdade.sail.repository.RegistroRepository;
import java.math.BigDecimal;

import com.faculdade.sail.model.Produto;
import com.faculdade.sail.repository.ProdutoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;





@Service
public class ProdutoService {


@Autowired
private RegistroRepository registroRepository;

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


    
    public Produto venderProduto(Long id, Integer quantidadeVendida) {
    // 1. Busca o produto no banco (usando o nome 'repository' original)
    Produto produto = repository.findById(id)
            .orElseThrow(() -> new RuntimeException("Produto não encontrado!"));

    // 2. Verifica se tem estoque suficiente
    if (produto.getQuantidade() < quantidadeVendida) {
        throw new RuntimeException("Estoque insuficiente para essa venda.");
    }

    // 3. Subtrai a quantidade e atualiza o produto
    produto.setQuantidade(produto.getQuantidade() - quantidadeVendida);
    Produto produtoAtualizado = repository.save(produto);

    // 4. MÁGICA DO EXTRATO: Calcula o valor e salva o Registro!
    BigDecimal preco = BigDecimal.valueOf(produto.getPreco()); 
    BigDecimal valorTotal = preco.multiply(BigDecimal.valueOf(quantidadeVendida));

    Registro novoRegistro = new Registro(produto, quantidadeVendida, valorTotal);
    registroRepository.save(novoRegistro);

    return produtoAtualizado;
}
}