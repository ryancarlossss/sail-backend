package com.faculdade.sail;

import com.faculdade.sail.model.Produto;
import com.faculdade.sail.model.Registro;
import com.faculdade.sail.model.Usuario;
import com.faculdade.sail.repository.ProdutoRepository;
import com.faculdade.sail.repository.RegistroRepository;
import com.faculdade.sail.repository.UsuarioRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ApiFluxosTests {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private ProdutoRepository produtos;
    @Autowired
    private RegistroRepository registros;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final JsonMapper json = JsonMapper.builder().build();
    private static final String SENHA = "Senha exclusiva de teste";

    @Test
    void documentacaoExigeAutenticacao() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("SESSAO_INVALIDA"));
    }

    @Test
    void documentacaoAutenticadaDescreveContratosDaApi() throws Exception {
        Acesso acesso = acesso();
        MvcResult resultado = mvc.perform(get("/v3/api-docs").header("Authorization", acesso.authorization()))
                .andExpect(status().isOk()).andReturn();
        JsonNode documento = arvore(resultado);
        assertThat(documento.get("openapi").asString()).startsWith("3.");
        assertThat(documento.get("paths").has("/produtos")).isTrue();
        assertThat(documento.get("paths").has("/usuarios/login")).isTrue();
    }

    @Test
    void exigeAutenticacaoSemExporDetalhes() throws Exception {
        mvc.perform(get("/produtos"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.codigo").value("SESSAO_INVALIDA"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void cadastraComSenhaCodificadaEAutenticaSemRetornarCredencial() throws Exception {
        String email = email();
        MvcResult cadastro = mvc.perform(post("/usuarios/cadastrar").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("nome", "Teste", "email", email, "senha", SENHA))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.senha").doesNotExist())
                .andExpect(jsonPath("$.senhaCodificada").doesNotExist()).andReturn();
        Usuario usuario = usuarios.findById(arvore(cadastro).get("id").asLong()).orElseThrow();
        assertThat(usuario.isSenhaCodificada()).isTrue();
        assertThat(usuario.getSenha()).isNotEqualTo(SENHA);
        assertThat(passwordEncoder.matches(SENHA, usuario.getSenha())).isTrue();
        JsonNode login = entrar(email, SENHA);
        assertThat(login.has("senha")).isFalse();
        assertThat(Instant.parse(login.get("expiraEm").asString())).isAfter(Instant.now());
        mvc.perform(get("/produtos").header("Authorization", "Bearer " + login.get("token").asString()))
                .andExpect(status().isOk());
        mvc.perform(post("/usuarios/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "senha", usuario.getSenha()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preservaLoginLegadoEConverteSomenteAposValidacao() throws Exception {
        Usuario usuario = usuarioLegado(SENHA);
        mvc.perform(post("/usuarios/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", usuario.getEmail(), "senha", "incorreta"))))
                .andExpect(status().isUnauthorized());
        assertThat(usuarios.findById(usuario.getId()).orElseThrow().getSenha()).isEqualTo(SENHA);
        entrar(usuario.getEmail(), SENHA);
        Usuario atualizado = usuarios.findById(usuario.getId()).orElseThrow();
        assertThat(atualizado.isSenhaCodificada()).isTrue();
        assertThat(passwordEncoder.matches(SENHA, atualizado.getSenha())).isTrue();
        String codificada = atualizado.getSenha();
        entrar(usuario.getEmail(), SENHA);
        assertThat(usuarios.findById(usuario.getId()).orElseThrow().getSenha()).isEqualTo(codificada);
    }

    @Test
    void senhaLegadaComAparenciaDeHashContinuaValida() throws Exception {
        String senha = "{pbkdf2}" + "a".repeat(96);
        Usuario legado = usuarioLegado(senha);
        entrar(legado.getEmail(), senha);
        Usuario atualizado = usuarios.findById(legado.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(senha, atualizado.getSenha())).isTrue();
    }

    @Test
    void rejeitaCadastroInvalidoSemGravar() throws Exception {
        long antes = usuarios.count();
        mvc.perform(post("/usuarios/cadastrar").contentType("application/json")
                        .content("{\"id\":1,\"nome\":\" \",\"email\":\"inválido\",\"senha\":\"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.id").exists())
                .andExpect(jsonPath("$.campos.email").exists())
                .andExpect(jsonPath("$.campos.senha").exists());
        assertThat(usuarios.count()).isEqualTo(antes);
    }

    @Test
    void emailDuplicadoRetornaConflito() throws Exception {
        Usuario legado = usuarioLegado(SENHA);
        mvc.perform(post("/usuarios/cadastrar").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("nome", "Teste", "email", legado.getEmail().toUpperCase(), "senha", SENHA))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CONFLITO"));
    }

    @Test
    void rejeitaTokenExpiradoOuAdulterado() throws Exception {
        Acesso acesso = acesso();
        Instant agora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("sail").audience(List.of("sail-app"))
                .subject(acesso.id().toString()).issuedAt(agora.minusSeconds(600)).notBefore(agora.minusSeconds(600))
                .expiresAt(agora.minusSeconds(120)).id(UUID.randomUUID().toString()).build();
        String expirado = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mvc.perform(get("/produtos").header("Authorization", "Bearer " + expirado))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.codigo").value("SESSAO_INVALIDA"));
        mvc.perform(get("/produtos").header("Authorization", "Bearer " + acesso.token() + "adulterado"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void naoPermiteFalsificarResponsavel() throws Exception {
        Acesso acesso = acesso();
        mvc.perform(post("/produtos").header("Authorization", acesso.authorization()).header("usuarioId", acesso.id() + 1)
                        .contentType("application/json").content(produtoJson("Produto " + UUID.randomUUID(), 10)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACESSO_NEGADO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"nome\":\" \",\"quantidade\":1,\"preco\":10}",
            "{\"nome\":\"Teste\",\"quantidade\":-1,\"preco\":10}",
            "{\"nome\":\"Teste\",\"quantidade\":1.5,\"preco\":10}",
            "{\"nome\":\"Teste\",\"quantidade\":2147483648,\"preco\":10}",
            "{\"nome\":\"Teste\",\"quantidade\":1,\"preco\":-1}",
            "{\"nome\":\"Teste\",\"quantidade\":1,\"preco\":1.234}",
            "{\"nome\":\"Teste\",\"quantidade\":null,\"preco\":10}"
    })
    void validaProdutoAntesDePersistir(String dados) throws Exception {
        Acesso acesso = acesso();
        long antes = produtos.count();
        mvc.perform(post("/produtos").header("Authorization", acesso.authorization())
                        .contentType("application/json").content(dados))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("DADOS_INVALIDOS"));
        assertThat(produtos.count()).isEqualTo(antes);
    }

    @Test
    void idInformadoNoCadastroNaoSobrescreveProduto() throws Exception {
        Acesso acesso = acesso();
        JsonNode original = criarProduto(acesso, 10, UUID.randomUUID().toString());
        long id = original.get("id").asLong();
        mvc.perform(post("/produtos").header("Authorization", acesso.authorization()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("id", id, "nome", "Adulterado", "quantidade", 0, "preco", 1))))
                .andExpect(status().isBadRequest());
        assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(10);
        assertThat(produtos.findById(id).orElseThrow().getNome()).isEqualTo(original.get("nome").asString());
    }

    @Test
    void fluxoCompletoPreservaHistoricoNaRemocao() throws Exception {
        Acesso acesso = acesso();
        JsonNode criado = criarProduto(acesso, 10, UUID.randomUUID().toString());
        long id = criado.get("id").asLong();
        MvcResult leitura = mvc.perform(get("/produtos/" + id).header("Authorization", acesso.authorization()))
                .andExpect(status().isOk()).andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.criadoPor.senha").doesNotExist())
                .andExpect(jsonPath("$.criadoPor.senhaCodificada").doesNotExist())
                .andExpect(jsonPath("$.ativo").doesNotExist()).andReturn();
        MvcResult edicao = mvc.perform(put("/produtos/" + id).header("Authorization", acesso.authorization())
                        .header("If-Match", leitura.getResponse().getHeader("ETag"))
                        .contentType("application/json").content(produtoJson("Produto editado", 12)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantidade").value(12)).andReturn();
        assertThat(edicao.getResponse().getHeader("ETag")).isNotEqualTo(leitura.getResponse().getHeader("ETag"));
        vender(acesso, id, 2, UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.quantidade").value(10));
        long antes = registrosDoProduto(id).size();
        mvc.perform(delete("/produtos/" + id).header("Authorization", acesso.authorization())).andExpect(status().isOk());
        assertThat(produtos.findById(id)).isPresent();
        assertThat(produtos.findById(id).orElseThrow().isAtivo()).isFalse();
        assertThat(registrosDoProduto(id).size()).isEqualTo(antes + 1);
        assertThat(registrosDoProduto(id)).anyMatch(registro -> "VENDA".equals(registro.getAcao()));
        JsonNode lista = arvore(mvc.perform(get("/produtos").header("Authorization", acesso.authorization())).andExpect(status().isOk()).andReturn());
        assertThat(StreamSupport.stream(lista.spliterator(), false).noneMatch(item -> item.get("id").asLong() == id)).isTrue();
        mvc.perform(get("/produtos/" + id).header("Authorization", acesso.authorization())).andExpect(status().isNotFound());
        vender(acesso, id, 1, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        mvc.perform(delete("/produtos/" + id).header("Authorization", acesso.authorization())).andExpect(status().isOk());
        assertThat(registrosDoProduto(id).stream().filter(registro -> "ARQUIVAMENTO".equals(registro.getAcao()))).hasSize(1);
    }

    @Test
    void repeticaoDeCadastroEVendaNaoDuplicaEfeitos() throws Exception {
        Acesso acesso = acesso();
        String nome = "Produto " + UUID.randomUUID();
        String chave = UUID.randomUUID().toString();
        String dados = produtoJson(nome, 10);
        JsonNode primeiro = arvore(mvc.perform(post("/produtos").header("Authorization", acesso.authorization()).header("Idempotency-Key", chave)
                        .contentType("application/json").content(dados)).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/produtos").header("Authorization", acesso.authorization()).header("Idempotency-Key", chave)
                        .contentType("application/json").content(dados))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(primeiro.get("id").asLong()));
        long id = primeiro.get("id").asLong();
        assertThat(registrosDoProduto(id)).hasSize(1);
        mvc.perform(post("/produtos").header("Authorization", acesso.authorization()).header("Idempotency-Key", chave)
                        .contentType("application/json").content(produtoJson(nome, 20)))
                .andExpect(status().isConflict());
        String chaveVenda = UUID.randomUUID().toString();
        vender(acesso, id, 2, chaveVenda).andExpect(status().isOk());
        vender(acesso, id, 2, chaveVenda).andExpect(status().isOk()).andExpect(jsonPath("$.quantidade").value(8));
        vender(acesso, id, 3, chaveVenda).andExpect(status().isConflict());
        assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(8);
        assertThat(registrosDoProduto(id).stream().filter(registro -> "VENDA".equals(registro.getAcao()))).hasSize(1);
    }

    @Test
    void edicaoAntigaNaoSobrescreveVendaConfirmada() throws Exception {
        Acesso acesso = acesso();
        long id = criarProduto(acesso, 10, UUID.randomUUID().toString()).get("id").asLong();
        String versao = mvc.perform(get("/produtos/" + id).header("Authorization", acesso.authorization())).andReturn().getResponse().getHeader("ETag");
        vender(acesso, id, 2, UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(put("/produtos/" + id).header("Authorization", acesso.authorization()).header("If-Match", versao)
                        .contentType("application/json").content(produtoJson("Edição antiga", 20)))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.codigo").value("PRODUTO_ALTERADO"));
        assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(8);
    }

    @Test
    void vendasSimultaneasNaoPerdemAtualizacaoNemExcedemSaldo() throws Exception {
        Acesso primeiro = acesso();
        Acesso segundo = acesso();
        long id = criarProduto(primeiro, 10, UUID.randomUUID().toString()).get("id").asLong();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            var venda1 = executor.submit(() -> { inicio.await(); return vender(primeiro, id, 7, UUID.randomUUID().toString()).andReturn(); });
            var venda2 = executor.submit(() -> { inicio.await(); return vender(segundo, id, 7, UUID.randomUUID().toString()).andReturn(); });
            inicio.countDown();
            int status1 = venda1.get(20, TimeUnit.SECONDS).getResponse().getStatus();
            int status2 = venda2.get(20, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(List.of(status1, status2).stream().filter(status -> status == 200)).hasSize(1);
            assertThat(status1 == 200 ? status2 : status1).isIn(400, 409);
            assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(3);
            assertThat(registrosDoProduto(id).stream().filter(registro -> "VENDA".equals(registro.getAcao()))).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mesmaChaveSimultaneaGeraUmaUnicaVenda() throws Exception {
        Acesso acesso = acesso();
        long id = criarProduto(acesso, 10, UUID.randomUUID().toString()).get("id").asLong();
        String chave = UUID.randomUUID().toString();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            var primeira = executor.submit(() -> { inicio.await(); return vender(acesso, id, 2, chave).andReturn(); });
            var segunda = executor.submit(() -> { inicio.await(); return vender(acesso, id, 2, chave).andReturn(); });
            inicio.countDown();
            assertThat(primeira.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(segunda.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(8);
            assertThat(registrosDoProduto(id).stream().filter(registro -> "VENDA".equals(registro.getAcao()))).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void historicoFiltraPeriodoEPreservaFormatoEValores() throws Exception {
        Acesso acesso = acesso();
        JsonNode antes = arvore(mvc.perform(get("/historico").header("Authorization", acesso.authorization())
                .param("mes", "1").param("ano", "1901")).andExpect(status().isOk()).andReturn());
        long id = criarProduto(acesso, 10, UUID.randomUUID().toString()).get("id").asLong();
        Produto produto = produtos.findById(id).orElseThrow();
        Usuario usuario = usuarios.findById(acesso.id()).orElseThrow();
        Registro janeiro = new Registro(produto, 1, BigDecimal.TEN, usuario);
        janeiro.setDataVenda(LocalDateTime.of(1901, 1, 15, 10, 0));
        janeiro = registros.save(janeiro);
        Registro fevereiro = new Registro(produto, 1, BigDecimal.valueOf(20), usuario);
        fevereiro.setDataVenda(LocalDateTime.of(1901, 2, 15, 10, 0));
        fevereiro = registros.save(fevereiro);
        JsonNode resposta = arvore(mvc.perform(get("/historico").header("Authorization", acesso.authorization())
                .param("mes", "1").param("ano", "1901")).andExpect(status().isOk()).andReturn());
        assertThat(resposta.get("totalDoMes").decimalValue()).isEqualByComparingTo(antes.get("totalDoMes").decimalValue().add(BigDecimal.TEN));
        List<Long> ids = StreamSupport.stream(resposta.get("lancamentos").spliterator(), false).map(item -> item.get("id").asLong()).toList();
        assertThat(ids).contains(janeiro.getId()).doesNotContain(fevereiro.getId());
        assertThat(resposta.toString()).doesNotContain("senha", "assinaturaOperacao", "chaveOperacao");
        mvc.perform(get("/historico").header("Authorization", acesso.authorization())).andExpect(status().isOk());
        mvc.perform(get("/historico").header("Authorization", acesso.authorization()).param("mes", "13").param("ano", "2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsPermiteCabecalhosNecessariosSemExigirSessaoNoPreflight() throws Exception {
        mvc.perform(options("/historico").header("Origin", "http://localhost:8081")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,usuarioId,if-match,idempotency-key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void listagensCarregamRelacionamentosSemConsultasPorItem() throws Exception {
        Acesso primeiro = acesso();
        Acesso segundo = acesso();
        criarProduto(primeiro, 10, UUID.randomUUID().toString());
        criarProduto(segundo, 10, UUID.randomUUID().toString());
        var estatisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean habilitadas = estatisticas.isStatisticsEnabled();
        estatisticas.setStatisticsEnabled(true);
        estatisticas.clear();
        try {
            mvc.perform(get("/produtos").header("Authorization", primeiro.authorization())).andExpect(status().isOk());
            long consultasProdutos = estatisticas.getPrepareStatementCount();
            estatisticas.clear();
            mvc.perform(get("/historico").header("Authorization", primeiro.authorization())).andExpect(status().isOk());
            long consultasHistorico = estatisticas.getPrepareStatementCount();
            org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertThat(consultasProdutos).as("Consultas da listagem de produtos").isEqualTo(1),
                    () -> assertThat(consultasHistorico).as("Consultas da listagem do histórico").isEqualTo(1));
        } finally {
            estatisticas.clear();
            estatisticas.setStatisticsEnabled(habilitadas);
        }
    }

    @Test
    void vendaExigePrecoRevisadoEPreservaRepeticaoAposConfirmacao() throws Exception {
        Acesso acesso = acesso();
        long id = criarProduto(acesso, 10, UUID.randomUUID().toString()).get("id").asLong();
        String antiga = mvc.perform(get("/produtos/" + id).header("Authorization", acesso.authorization()))
                .andReturn().getResponse().getHeader("ETag");
        String nova = mvc.perform(put("/produtos/" + id).header("Authorization", acesso.authorization()).header("If-Match", antiga)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("nome", "Preço atualizado", "quantidade", 10, "preco", 20))))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
        String chave = UUID.randomUUID().toString();
        mvc.perform(post("/produtos/" + id + "/vender").header("Authorization", acesso.authorization())
                        .header("Idempotency-Key", chave).header("If-Match", antiga).param("quantidade", "2"))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.codigo").value("PRODUTO_ALTERADO"));
        assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(10);
        assertThat(registrosDoProduto(id)).noneMatch(registro -> "VENDA".equals(registro.getAcao()));
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            mvc.perform(post("/produtos/" + id + "/vender").header("Authorization", acesso.authorization())
                            .header("Idempotency-Key", chave).header("If-Match", nova).param("quantidade", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.quantidade").value(8));
        }
        assertThat(registrosDoProduto(id).stream().filter(registro -> "VENDA".equals(registro.getAcao())))
                .singleElement().satisfies(registro -> assertThat(registro.getValorTotal()).isEqualByComparingTo("40.00"));
    }

    @Test
    void confirmacoesSimultaneasDaMesmaVersaoExigemRevisarSaldoAlterado() throws Exception {
        Acesso primeiro = acesso();
        Acesso segundo = acesso();
        long id = criarProduto(primeiro, 10, UUID.randomUUID().toString()).get("id").asLong();
        String versao = mvc.perform(get("/produtos/" + id).header("Authorization", primeiro.authorization()))
                .andReturn().getResponse().getHeader("ETag");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        try {
            var tarefas = List.of(primeiro, segundo).stream().map(acesso -> executor.submit(() -> {
                inicio.await();
                return mvc.perform(post("/produtos/" + id + "/vender").header("Authorization", acesso.authorization())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).header("If-Match", versao).param("quantidade", "2"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            inicio.countDown();
            assertThat(List.of(tarefas.get(0).get(20, TimeUnit.SECONDS), tarefas.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(8);
            assertThat(registrosDoProduto(id).stream().filter(registro -> "VENDA".equals(registro.getAcao()))).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void precoLegadoComFracaoDeCentavoNaoGeraVendaDiferenteDaPrevia() throws Exception {
        Acesso acesso = acesso();
        long id = criarProduto(acesso, 10, UUID.randomUUID().toString()).get("id").asLong();
        Produto produto = produtos.findById(id).orElseThrow();
        produto.setPreco(1.234);
        produtos.save(produto);
        vender(acesso, id, 3, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertThat(produtos.findById(id).orElseThrow().getQuantidade()).isEqualTo(10);
        assertThat(registrosDoProduto(id)).noneMatch(registro -> "VENDA".equals(registro.getAcao()));
    }

    @Test
    void resumoContaProdutosSemEstoqueForaDaPrimeiraPaginaEIgnoraArquivados() throws Exception {
        Acesso acesso = acesso();
        JsonNode antes = arvore(mvc.perform(get("/produtos/resumo").header("Authorization", acesso.authorization())).andReturn());
        long id = criarProduto(acesso, 0, UUID.randomUUID().toString()).get("id").asLong();
        for (int i = 0; i < 51; i++) criarProduto(acesso, 1, UUID.randomUUID().toString());
        JsonNode pagina = arvore(mvc.perform(get("/produtos/pagina").header("Authorization", acesso.authorization())).andReturn());
        assertThat(StreamSupport.stream(pagina.get("itens").spliterator(), false).map(item -> item.get("id").asLong())).doesNotContain(id);
        mvc.perform(get("/produtos/resumo").header("Authorization", acesso.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantidadeSemEstoque").value(antes.get("quantidadeSemEstoque").asLong() + 1));
        mvc.perform(delete("/produtos/" + id).header("Authorization", acesso.authorization())).andExpect(status().isOk());
        mvc.perform(get("/produtos/resumo").header("Authorization", acesso.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantidadeSemEstoque").value(antes.get("quantidadeSemEstoque").asLong()));
    }

    @Test
    void indicadoresRefletemVendaAteZeroEReposicaoConfirmada() throws Exception {
        Acesso acesso = acesso();
        JsonNode antes = arvore(mvc.perform(get("/produtos/resumo").header("Authorization", acesso.authorization())).andReturn());
        long id = criarProduto(acesso, 2, UUID.randomUUID().toString()).get("id").asLong();
        vender(acesso, id, 2, UUID.randomUUID().toString()).andExpect(status().isOk());
        mvc.perform(get("/produtos/resumo").header("Authorization", acesso.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantidadeSemEstoque").value(antes.get("quantidadeSemEstoque").asLong() + 1));
        mvc.perform(put("/produtos/" + id).header("Authorization", acesso.authorization()).contentType("application/json")
                        .content(produtoJson("Reposição de teste", 3))).andExpect(status().isOk());
        JsonNode reposto = arvore(mvc.perform(get("/produtos/resumo").header("Authorization", acesso.authorization())).andReturn());
        assertThat(reposto.get("quantidadeSemEstoque").asLong()).isEqualTo(antes.get("quantidadeSemEstoque").asLong());
        assertThat(reposto.get("valorEstoque").decimalValue()).isEqualByComparingTo(antes.get("valorEstoque").decimalValue().add(new BigDecimal("37.50")));
        assertThat(registrosDoProduto(id).stream().filter(registro -> "AJUSTE".equals(registro.getAcao())))
                .singleElement().satisfies(registro -> assertThat(registro.getQuantidade()).isEqualTo(3));
    }

    private Acesso acesso() throws Exception {
        Usuario usuario = usuarioLegado(SENHA);
        JsonNode login = entrar(usuario.getEmail(), SENHA);
        return new Acesso(usuario.getId(), login.get("token").asString());
    }

    private Usuario usuarioLegado(String senha) {
        Usuario usuario = new Usuario();
        usuario.setNome("Usuário de teste");
        usuario.setEmail(email());
        usuario.setSenha(senha);
        return usuarios.save(usuario);
    }

    private JsonNode entrar(String email, String senha) throws Exception {
        return arvore(mvc.perform(post("/usuarios/login").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("email", email, "senha", senha))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isString()).andReturn());
    }

    private JsonNode criarProduto(Acesso acesso, int quantidade, String chave) throws Exception {
        return arvore(mvc.perform(post("/produtos").header("Authorization", acesso.authorization())
                        .header("Idempotency-Key", chave).contentType("application/json")
                        .content(produtoJson("Produto " + UUID.randomUUID(), quantidade)))
                .andExpect(status().isOk()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions vender(Acesso acesso, long id, int quantidade, String chave) throws Exception {
        return mvc.perform(post("/produtos/" + id + "/vender").header("Authorization", acesso.authorization())
                .header("Idempotency-Key", chave).param("quantidade", Integer.toString(quantidade)));
    }

    private List<Registro> registrosDoProduto(long id) {
        return registros.findAll().stream().filter(registro -> registro.getProduto().getId().equals(id)).toList();
    }

    private String produtoJson(String nome, int quantidade) {
        return json.writeValueAsString(Map.of("nome", nome, "quantidade", quantidade, "preco", 12.5));
    }

    private JsonNode arvore(MvcResult resultado) throws java.io.UnsupportedEncodingException {
        return json.readTree(resultado.getResponse().getContentAsString());
    }

    private String email() { return UUID.randomUUID() + "@example.invalid"; }

    private record Acesso(Long id, String token) {
        String authorization() { return "Bearer " + token; }

        @Override
        public String toString() { return "Acesso de teste [id=" + id + "]"; }
    }
}
