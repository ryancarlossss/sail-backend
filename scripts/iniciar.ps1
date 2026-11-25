param(
    [ValidateSet('Iniciar', 'Frontend', 'Backend', 'ReiniciarFrontend', 'Parar', 'Status')]
    [string]$Acao = 'Iniciar',
    [string]$IP,
    [ValidateRange(30, 300)]
    [int]$TempoEspera = 120
)

$ErrorActionPreference = 'Stop'
if ($IP) {
    $validado = $null
    if ($IP -notmatch '^\d{1,3}(?:\.\d{1,3}){3}$' -or ![Net.IPAddress]::TryParse($IP, [ref]$validado) -or
        $validado.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $validado.Equals([Net.IPAddress]::Any) -or $validado.Equals([Net.IPAddress]::Broadcast)) {
        throw 'Informe um endereco IPv4 valido.'
    }
    $IP = $validado.ToString()
}
$backend = Split-Path -Parent $PSScriptRoot
$repositorios = Split-Path -Parent $backend
$raiz = Split-Path -Parent $repositorios
$ferramentas = Join-Path $raiz '.sail-tools'
[void][IO.Directory]::CreateDirectory($ferramentas)
$frontend = Join-Path $repositorios 'frontend'
$arquivoEstado = Join-Path $ferramentas 'execucao-sail.json'
$estado = @{}
if (Test-Path -LiteralPath $arquivoEstado) {
    $anterior = Get-Content -LiteralPath $arquivoEstado -Raw | ConvertFrom-Json
    foreach ($propriedade in $anterior.PSObject.Properties) { $estado[$propriedade.Name] = $propriedade.Value }
}

function Salvar-Estado {
    [IO.File]::WriteAllText($arquivoEstado, (ConvertTo-Json -InputObject $estado -Depth 4), [Text.UTF8Encoding]::new($false))
}

function Obter-Processo([string]$Servico) {
    if (!$estado.ContainsKey($Servico)) { return $null }
    $registro = $estado[$Servico]
    $processo = Get-Process -Id $registro.pid -ErrorAction SilentlyContinue
    if ($null -ne $processo -and $processo.StartTime.ToUniversalTime().Ticks -eq [long]$registro.inicio) { return $processo }
    return $null
}

function Obter-StatusHttp([string]$Endereco) {
    $requisicao = [Net.HttpWebRequest]::Create($Endereco)
    $requisicao.Timeout = 2000
    $resposta = $null
    try {
        $resposta = $requisicao.GetResponse()
        return [int]$resposta.StatusCode
    } catch [Net.WebException] {
        if ($null -ne $_.Exception.Response) {
            $resposta = $_.Exception.Response
            return [int]$resposta.StatusCode
        }
        return 0
    } finally {
        if ($null -ne $resposta) { $resposta.Dispose() }
    }
}

function Registrar-Processo([string]$Servico, $Processo) {
    $estado[$Servico] = @{ pid = $Processo.Id; inicio = $Processo.StartTime.ToUniversalTime().Ticks }
    Salvar-Estado
}

function Aguardar-Servico($Processo, [string]$Endereco, [int]$Esperado, [string]$Log) {
    $limite = [DateTime]::UtcNow.AddSeconds($TempoEspera)
    while ([DateTime]::UtcNow -lt $limite) {
        $Processo.Refresh()
        if ($Processo.HasExited) { throw "O servico encerrou. Consulte $Log" }
        if ((Obter-StatusHttp $Endereco) -eq $Esperado) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "O servico nao respondeu a tempo. Consulte $Log"
}

function Testar-PortaBanco {
    $conexao = [Net.Sockets.TcpClient]::new()
    try {
        $tarefa = $conexao.ConnectAsync('127.0.0.1', 3306)
        if (!$tarefa.Wait(1500)) { return $false }
        return $conexao.Connected
    } catch [AggregateException] {
        return $false
    } finally { $conexao.Dispose() }
}

function Confirmar-Banco {
    if (Testar-PortaBanco) { return }
    $servico = Get-Service -Name 'MySQL80' -ErrorAction SilentlyContinue
    if (!$servico) { throw 'O MySQL nao esta disponivel na porta 3306. Inicie o banco existente antes da apresentacao.' }
    try { Start-Service -Name $servico.Name -ErrorAction Stop } catch {
        throw 'Nao foi possivel iniciar o servico MySQL80. Inicie-o pelo painel Servicos do Windows e tente novamente.'
    }
    $limite = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $limite) {
        if (Testar-PortaBanco) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'O MySQL nao respondeu na porta 3306. Nenhum banco foi criado ou alterado.'
}

function Confirmar-LoginBanco {
    $requisicao = [Net.HttpWebRequest]::Create('http://127.0.0.1:8080/usuarios/login')
    $requisicao.Method = 'POST'
    $requisicao.Timeout = 15000
    $requisicao.ContentType = 'application/json; charset=utf-8'
    $corpo = @{ email = ([guid]::NewGuid().ToString() + '@example.invalid'); senha = 'Verificacao local de disponibilidade' } | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($corpo)
    $requisicao.ContentLength = $bytes.Length
    $fluxo = $requisicao.GetRequestStream()
    try { $fluxo.Write($bytes, 0, $bytes.Length) } finally { $fluxo.Dispose() }
    $resposta = $null
    try {
        $resposta = $requisicao.GetResponse()
        throw 'A verificacao de login retornou uma resposta inesperada.'
    } catch [Net.WebException] {
        $resposta = $_.Exception.Response
        if (!$resposta -or [int]$resposta.StatusCode -ne 401) { throw 'O login nao conseguiu validar o acesso ao banco. Consulte .sail-tools/sail-backend.log.' }
        $leitor = [IO.StreamReader]::new($resposta.GetResponseStream(), [Text.Encoding]::UTF8)
        try { $erro = $leitor.ReadToEnd() | ConvertFrom-Json } finally { $leitor.Dispose() }
        if ($erro.codigo -ne 'CREDENCIAIS_INVALIDAS') { throw 'A API nao confirmou a consulta ao banco. Consulte .sail-tools/sail-backend.log.' }
    } finally { if ($resposta) { $resposta.Dispose() } }
}

if ($Acao -in @('Parar', 'ReiniciarFrontend')) {
    $servicos = if ($Acao -eq 'Parar') { @('frontend', 'backend') } else { @('frontend') }
    foreach ($servico in $servicos) {
        $processo = Obter-Processo $servico
        if ($null -ne $processo) {
            $todos = @(Get-CimInstance Win32_Process)
            $descendentes = @($processo.Id)
            for ($indice = 0; $indice -lt $descendentes.Count; $indice++) {
                $descendentes += @($todos | Where-Object { $_.ParentProcessId -eq $descendentes[$indice] } | ForEach-Object { $_.ProcessId })
            }
            for ($indice = $descendentes.Count - 1; $indice -ge 0; $indice--) {
                $anterior = $todos | Where-Object ProcessId -eq $descendentes[$indice]
                $ativo = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $descendentes[$indice])
                if ($null -ne $ativo -and $ativo.CreationDate -eq $anterior.CreationDate) { Stop-Process -Id $ativo.ProcessId -ErrorAction Stop }
            }
            $porta = if ($servico -eq 'frontend') { 8081 } else { 8080 }
            $limite = [DateTime]::UtcNow.AddSeconds(15)
            while (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq $porta).Count) {
                if ([DateTime]::UtcNow -ge $limite) { throw "A porta $porta nao foi liberada apos encerrar o Sail." }
                Start-Sleep -Milliseconds 100
            }
        }
        $estado.Remove($servico)
    }
    Salvar-Estado
    if ($Acao -eq 'Parar') {
        Write-Output 'Frontend e backend do Sail encerrados. O MySQL permanece ativo.'
        exit 0
    }
    $Acao = 'Frontend'
}

if ($Acao -eq 'Status') {
    Write-Output ('MySQL: ' + $(if (Testar-PortaBanco) { 'ativo' } else { 'indisponivel' }))
    foreach ($servico in @('backend', 'frontend')) {
        $processo = Obter-Processo $servico
        $situacao = if ($null -eq $processo) { 'parado' } else { 'ativo (PID ' + $processo.Id + ')' }
        Write-Output ($servico + ': ' + $situacao)
    }
    Write-Output ('API HTTP: ' + (Obter-StatusHttp 'http://127.0.0.1:8080/produtos/resumo'))
    Write-Output ('Expo HTTP: ' + (Obter-StatusHttp 'http://127.0.0.1:8081/status'))
    if ($estado.ContainsKey('ip')) { Write-Output ('Ultimo IP do Expo: ' + $estado['ip']) }
    exit 0
}

if (!$IP) {
    $redes = [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | Where-Object {
        $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' -and $_.GetIPProperties().GatewayAddresses.Count -gt 0
    } | Sort-Object { if ($_.NetworkInterfaceType -eq 'Wireless80211') { 0 } else { 1 } }
    $IP = @($redes | ForEach-Object { $_.GetIPProperties().UnicastAddresses } | Where-Object {
        $_.Address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and !$_.Address.ToString().StartsWith('169.254.')
    } | ForEach-Object { $_.Address.ToString() } | Select-Object -First 1)[0]
    if (!$IP) { $IP = '127.0.0.1'; Write-Output 'Sem rede local: a apresentacao esta disponivel somente neste computador.' }
}
$endereco = $null
if (![Net.IPAddress]::TryParse($IP, [ref]$endereco) -or $endereco.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
    throw 'Informe um endereco IPv4 valido.'
}
Write-Output ('IP local: ' + $IP)

if ($Acao -in @('Iniciar', 'Backend', 'Frontend')) {
    Confirmar-Banco
    $processoBackend = Obter-Processo 'backend'
    if ($null -eq $processoBackend) {
        if (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 8080).Count) { throw 'A porta 8080 esta ocupada por outro processo.' }
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $jar = Join-Path $backend 'target/sail-0.0.1-SNAPSHOT.jar'
        $fontes = @(Get-ChildItem -LiteralPath (Join-Path $backend 'src/main') -Recurse -File) + @(Get-Item -LiteralPath (Join-Path $backend 'pom.xml'))
        $recompilar = !(Test-Path -LiteralPath $jar)
        if (!$recompilar) { $recompilar = @($fontes | Where-Object { $_.LastWriteTimeUtc -gt (Get-Item -LiteralPath $jar).LastWriteTimeUtc }).Count -gt 0 }
        if ($recompilar) {
            $maven = Get-ChildItem -LiteralPath (Join-Path $ferramentas 'maven-home') -Recurse -File -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
            if (!$maven) { $maven = Join-Path $backend 'mvnw.cmd' }
            $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
            Push-Location $backend
            try {
                & $maven ("-Dmaven.repo.local=" + (Join-Path $ferramentas 'm2')) -DskipTests package *> (Join-Path $ferramentas 'inicializacao-build.log')
                if ($LASTEXITCODE -ne 0) { throw 'Falha no build Java. Consulte .sail-tools/inicializacao-build.log.' }
            } finally { Pop-Location }
        }
        $argumentos = @('-jar', ('"' + $jar + '"'), '--server.address=0.0.0.0', '--server.port=8080', '--spring.jpa.hibernate.ddl-auto=validate', '--spring.jpa.show-sql=false', '--logging.level.org.hibernate.SQL=WARN', '--logging.level.org.hibernate.orm.jdbc.bind=OFF')
        $processoBackend = Start-Process -FilePath $java -ArgumentList $argumentos -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $ferramentas 'sail-backend.log') -RedirectStandardError (Join-Path $ferramentas 'sail-backend-erro.log') -PassThru
        Registrar-Processo 'backend' $processoBackend
    }
    Aguardar-Servico $processoBackend 'http://127.0.0.1:8080/produtos/resumo' 401 '.sail-tools/sail-backend.log'
    Confirmar-LoginBanco
    Write-Output ('API pronta: http://' + $IP + ':8080')
}

if ($Acao -in @('Iniciar', 'Frontend')) {
    $processoFrontend = Obter-Processo 'frontend'
    if ($null -ne $processoFrontend -and $estado.ContainsKey('ip') -and $estado['ip'] -ne $IP) {
        & $PSCommandPath -Acao ReiniciarFrontend -IP $IP -TempoEspera $TempoEspera
        exit $LASTEXITCODE
    }
    if ($null -eq $processoFrontend) {
        if (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -eq 8081).Count) { throw 'A porta 8081 esta ocupada por outro processo.' }
        $iniciadorFrontend = Join-Path $frontend 'scripts/iniciar.ps1'
        if (!(Test-Path -LiteralPath $iniciadorFrontend)) { throw 'O frontend com scripts/iniciar.ps1 precisa estar na pasta irma frontend.' }
        $preparacao = & $iniciadorFrontend -PrepararApenas -IP $IP
        $frontend = $preparacao.PastaExecucao
        if (!$frontend) { throw 'A copia de execucao do frontend nao foi preparada.' }
        $cli = Join-Path $frontend 'node_modules/expo/bin/cli'
        if (!(Test-Path -LiteralPath $cli)) { throw 'As dependencias do frontend nao estao instaladas.' }
        $variaveis = @{ EXPO_PUBLIC_API_URL = ('http://' + $IP + ':8080'); REACT_NATIVE_PACKAGER_HOSTNAME = $IP; EXPO_HOME = (Join-Path $ferramentas 'expo-home'); EXPO_OFFLINE = '1'; EXPO_NO_TELEMETRY = '1' }
        $anteriores = @{}
        foreach ($nome in $variaveis.Keys) {
            $anteriores[$nome] = [Environment]::GetEnvironmentVariable($nome, 'Process')
            [Environment]::SetEnvironmentVariable($nome, $variaveis[$nome], 'Process')
        }
        try {
            $processoFrontend = Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @(('"' + $cli + '"'), 'start', '--lan', '--port', '8081', '--max-workers', '1') -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $ferramentas 'sail-frontend.log') -RedirectStandardError (Join-Path $ferramentas 'sail-frontend-erro.log') -PassThru
        } finally {
            foreach ($nome in $anteriores.Keys) { [Environment]::SetEnvironmentVariable($nome, $anteriores[$nome], 'Process') }
        }
        $estado['ip'] = $IP
        $estado['pastaFrontend'] = $frontend
        Registrar-Processo 'frontend' $processoFrontend
    }
    Aguardar-Servico $processoFrontend 'http://127.0.0.1:8081/status' 200 '.sail-tools/sail-frontend.log'
    Write-Output 'Navegador neste computador: http://localhost:8081'
    Write-Output ('Navegador pela rede: http://' + $IP + ':8081')
    Write-Output ('Expo Go: exp://' + $IP + ':8081')
    $pastaQr = if ($estado.ContainsKey('pastaFrontend')) { $estado['pastaFrontend'] } else { $frontend }
    $qr = Join-Path $pastaQr 'node_modules/qrcode-terminal/lib/main.js'
    if (Test-Path -LiteralPath $qr) { & (Get-Command node.exe).Source -e "require(process.argv[1]).generate(process.argv[2], {small:true})" $qr ('exp://' + $IP + ':8081') }
}

exit 0
