package com.faculdade.sail.dtos;

import java.math.BigDecimal;
import java.util.List;

// Aqui apresentamos a classe Registro para o HistoricoDTO
import com.faculdade.sail.model.Registro;

public class HistoricoDTO {

    private BigDecimal totalDoMes; // Vai alimentar a cor AMARELA na tela
    private List<Registro> lancamentos; // Vai alimentar a cor ROSA na tela

    public HistoricoDTO(BigDecimal totalDoMes, List<Registro> lancamentos) {
        this.totalDoMes = totalDoMes;
        this.lancamentos = lancamentos;
    }

    // Getters
    public BigDecimal getTotalDoMes() { return totalDoMes; }
    public void setTotalDoMes(BigDecimal totalDoMes) { this.totalDoMes = totalDoMes; }

    public List<Registro> getLancamentos() { return lancamentos; }
    public void setLancamentos(List<Registro> lancamentos) { this.lancamentos = lancamentos; }
}