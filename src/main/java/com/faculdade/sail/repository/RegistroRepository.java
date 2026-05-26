package com.faculdade.sail.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;


import com.faculdade.sail.model.Registro;

@Repository
public interface RegistroRepository extends JpaRepository<Registro, Long> {

    @Query("SELECT r FROM Registro r WHERE MONTH(r.dataVenda) = :mes AND YEAR(r.dataVenda) = :ano ORDER BY r.dataVenda DESC")
    List<Registro> buscarVendasPorMesEAno(@Param("mes") int mes, @Param("ano") int ano);
}