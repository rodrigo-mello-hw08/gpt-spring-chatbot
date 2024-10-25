package br.com.alura.ecomart.chatbot.infra.openai;

import br.com.alura.ecomart.chatbot.domain.DadosCalculoFrete;
import br.com.alura.ecomart.chatbot.domain.service.CalculadorDeFrete;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.completion.chat.ChatFunction;
import com.theokanning.openai.completion.chat.ChatFunctionCall;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.messages.Message;
import com.theokanning.openai.messages.MessageRequest;
import com.theokanning.openai.runs.Run;
import com.theokanning.openai.runs.RunCreateRequest;
import com.theokanning.openai.runs.SubmitToolOutputRequestItem;
import com.theokanning.openai.runs.SubmitToolOutputsRequest;
import com.theokanning.openai.service.FunctionExecutor;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.threads.ThreadRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class OpenAIClient {

    private final String apiKey;
    private final String assistantId;
    private final OpenAiService service;
    private final CalculadorDeFrete calculadorDeFrete;
    private String threadId;

    public OpenAIClient(
            @Value("${app.openai.api.key}") String apiKey,
            @Value("${app.openai.assistant.id}") String assistantId,
            CalculadorDeFrete calculadorDeFrete
    ) {
        this.apiKey = apiKey;
        this.assistantId = assistantId;
        this.service = new OpenAiService(apiKey, Duration.ofSeconds(60));
        this.calculadorDeFrete = calculadorDeFrete;
    }

    public String enviarRequisicaoChatCompletion(DadosRequisicaoChatCompletion dados) {
        MessageRequest messageRequest = MessageRequest.builder()
            .role(ChatMessageRole.USER.value())
            .content(dados.promptUsuario())
            .build();

        criarMensagem(messageRequest);

        RunCreateRequest runRequest = RunCreateRequest.builder()
            .assistantId(assistantId)
            .build();
        Run run = service.createRun(threadId, runRequest);

        esperarSucessoMensagem(run);
        return obterMensagem();
    }

    private void criarMensagem(MessageRequest messageRequest) {
        if (this.threadId == null) {
            ThreadRequest threadRequest = ThreadRequest.builder()
                .messages(Arrays.asList(messageRequest))
                .build();
            var thread = service.createThread(threadRequest);
            this.threadId = thread.getId();
        } else {
            service.createMessage(this.threadId, messageRequest);
        }
    }

    private void esperarSucessoMensagem(Run run) {
        var concluido = false;
        var precisaChamarFuncao = false;
        try {
            while (!concluido && !precisaChamarFuncao) {
                Thread.sleep(1000 * 10);
                run = service.retrieveRun(threadId, run.getId());
                concluido = run.getStatus().equalsIgnoreCase("completed");
                precisaChamarFuncao = run.getRequiredAction() != null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (precisaChamarFuncao) {
            chamarFuncao(run, concluido);
        }
    }

    private void chamarFuncao(Run run, boolean concluido) {
        var precoDoFrete = executarFuncao(run);
        String id = run.getRequiredAction()
                .getSubmitToolOutputs()
                .getToolCalls()
                .get(0)
                .getId();
        List<SubmitToolOutputRequestItem> toolOutputs = Arrays.asList(
            new SubmitToolOutputRequestItem(id, precoDoFrete)
        );
        var submitRequest = SubmitToolOutputsRequest
            .builder()
            .toolOutputs(toolOutputs)
            .build();
        service.submitToolOutputs(threadId, run.getId(), submitRequest);

        verificarTerminoFuncao(run, concluido);
    }

    private void verificarTerminoFuncao(Run run, boolean concluido) {
        try {
            while (!concluido) {
                Thread.sleep(1000 * 10);
                run = service.retrieveRun(threadId, run.getId());
                concluido = run.getStatus().equalsIgnoreCase("completed");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String executarFuncao(Run run) {
        try {
            var funcao = run.getRequiredAction().getSubmitToolOutputs().getToolCalls().get(0).getFunction();
            var funcaoCalcularFrete = ChatFunction.builder()
                    .name("calcularFrete")
                    .executor(DadosCalculoFrete.class, calculadorDeFrete::calcular)
                    .build();

            var executorDeFuncoes = new FunctionExecutor(Arrays.asList(funcaoCalcularFrete));
            var functionCall = new ChatFunctionCall(funcao.getName(), new ObjectMapper().readTree(funcao.getArguments()));
            return executorDeFuncoes.execute(functionCall).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String obterMensagem() {
        OpenAiResponse<Message> mensagens = service.listMessages(threadId);
        Optional<Message> mensagem = mensagens.getData().stream()
                .sorted(Comparator.comparingInt(Message::getCreatedAt).reversed())
                .findFirst();
        if (mensagem.isEmpty()) throw new RuntimeException("Mensagem vazia");

        return mensagem.get().getContent().get(0).getText().getValue();
    }

    public List<String> carregarHistoricoDeMensagens() {
        ArrayList<String> mensagens = new ArrayList<>();
        if (this.threadId != null) {
            List<String> listaMensagens = service.listMessages(this.threadId).getData()
                .stream()
                .sorted(Comparator.comparingInt(Message::getCreatedAt))
                .map(m -> m.getContent().get(0).getText().getValue())
                .toList();
            mensagens.addAll(listaMensagens);
        }
        return mensagens;
    }

    public void apagarThread() {
        if (this.threadId != null) {
            service.deleteThread(this.threadId);
            this.threadId = null;
        }
    }

}
