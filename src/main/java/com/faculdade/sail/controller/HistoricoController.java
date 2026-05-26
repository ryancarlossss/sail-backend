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
            @RequestParam int mes,
            @RequestParam int ano) {

        
        List<Registro> lancamentos = registroRepository.buscarVendasPorMesEAno(mes, ano);

        
        BigDecimal totalDoMes = BigDecimal.ZERO;
        for (Registro registro : lancamentos) {
            totalDoMes = totalDoMes.add(registro.getValorTotal());
        }

        
        HistoricoDTO historicoDTO = new HistoricoDTO(totalDoMes, lancamentos);

        return ResponseEntity.ok(historicoDTO);
    }
}