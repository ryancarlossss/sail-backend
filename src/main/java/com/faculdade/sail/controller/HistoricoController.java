package com.faculdade.sail.controller;

import com.faculdade.sail.dtos.HistoricoDTO;
import com.faculdade.sail.model.Registro;
import com.faculdade.sail.repository.RegistroRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/historico")
public class HistoricoController {

    @Autowired
    private RegistroRepository registroRepository;

    @GetMapping
    public ResponseEntity<HistoricoDTO> obterHistorico(
            @RequestParam(required = false, defaultValue = "0") int mes,
            @RequestParam(required = false, defaultValue = "0") int ano) {

        
        List<Registro> lancamentos = registroRepository.findAll();

        BigDecimal totalDoMes = BigDecimal.ZERO;
        for (Registro registro : lancamentos) {
            
            if (registro.getValorTotal() != null) {
                totalDoMes = totalDoMes.add(registro.getValorTotal());
            }
        }

        HistoricoDTO historicoDTO = new HistoricoDTO(totalDoMes, lancamentos);

        return ResponseEntity.ok(historicoDTO);
    }
}