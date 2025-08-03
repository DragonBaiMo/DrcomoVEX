package cn.drcomo;

import cn.drcomo.managers.MessagesManager;
import cn.drcomo.managers.VariablesManager;
import cn.drcomo.model.VariableResult;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主指令单元测试
 */
public class MainCommandTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("TestPlayer");
        player.addAttachment(MockBukkit.createMockPlugin(), "drcomovex.command.set", true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void testSetCommandFailureShowsOperationFailed() {
        TestMessagesManager messagesManager = new TestMessagesManager();
        TestVariablesManager variablesManager = new TestVariablesManager();

        MainCommand command = new MainCommand(null, null, messagesManager, variablesManager, null, null);

        command.onCommand(player, null, "vex", new String[]{"set", "test", "123"});

        assertEquals("error.operation-failed", messagesManager.lastKey);
        assertEquals("测试失败", messagesManager.lastPlaceholders.get("reason"));
    }

    /**
     * 失败结果的变量管理器
     */
    private static class TestVariablesManager extends VariablesManager {
        public TestVariablesManager() {
            super(null, null, null, null, null, null);
        }

        @Override
        public CompletableFuture<VariableResult> setVariable(OfflinePlayer player, String key, String value) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("测试失败", "SET", key, player.getName())
            );
        }
    }

    /**
     * 记录消息的消息管理器
     */
    private static class TestMessagesManager extends MessagesManager {
        public CommandSender lastSender;
        public String lastKey;
        public Map<String, String> lastPlaceholders;

        public TestMessagesManager() {
            super(null, null, null);
        }

        @Override
        public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
            this.lastSender = sender;
            this.lastKey = messageKey;
            this.lastPlaceholders = placeholders;
        }

        @Override
        public void sendMessage(CommandSender sender, String messageKey) {
            sendMessage(sender, messageKey, null);
        }
    }
}
