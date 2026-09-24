package dev.micolash.jasm.gametest;

import dev.micolash.jasm.Jasm;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers every JASM GameTest. Suites add themselves in the static block. Run with {@code gradlew runGameTestServer}. */
public final class JasmGameTests {
    public static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, Jasm.MODID);

    private static final List<String> NAMES = new ArrayList<>();

    static {
        SmokeGameTests.register();
        dev.micolash.jasm.storage.StorageGameTests.register();
        ValidatorGameTests.register();
    }

    private JasmGameTests() {}

    public static void add(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> test);
        NAMES.add(name);
    }

    public static void onRegisterTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(Jasm.id("default"));
        for (String name : NAMES) {
            Identifier id = Jasm.id(name);
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 100, 0, true);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id), data));
        }
    }
}
