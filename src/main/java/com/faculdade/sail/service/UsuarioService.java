package com.faculdade.sail.service;

import com.faculdade.sail.model.Usuario;
import com.faculdade.sail.repository.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UsuarioService {

    @Autowired
    private UsuarioRepository repository;

    public Usuario cadastrar(Usuario usuario) {
        
        if(repository.findByEmail(usuario.getEmail()).isPresent()) {
            throw new RuntimeException("Este e-mail já está em uso!");
        }
        return repository.save(usuario);
    }

    public Usuario fazerLogin(String email, String senha) {
        
        Optional<Usuario> usuario = repository.findByEmailAndSenha(email, senha);
        
        
        return usuario.orElseThrow(() -> new RuntimeException("E-mail ou senha incorretos!"));
    }
}