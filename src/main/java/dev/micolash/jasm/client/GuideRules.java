package dev.micolash.jasm.client;

import dev.micolash.jasm.config.Feature;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.core.BrainBalance;
import dev.micolash.jasm.deck.DeckTier;
import guideme.compiler.PageCompiler;
import guideme.compiler.tags.BlockTagCompiler;
import guideme.compiler.tags.FlowTagCompiler;
import guideme.compiler.tags.MdxAttrs;
import guideme.document.block.LytBlockContainer;
import guideme.document.flow.LytFlowParent;
import guideme.libs.mdast.mdx.model.MdxJsxElementFields;
import java.util.Locale;
import java.util.Set;

/**
 * Guide tags that read the world's rules when a page opens: {@code <Rule name="deck.slots.ultimate" />} prints a number,
 * and {@code <WhenOn feature="bays">} / {@code <WhenOff feature="bays">} show their contents only in that state. With no
 * world loaded (title screen, website export) they show the standard numbers.
 */
final class GuideRules {
    private GuideRules() {}

    static String value(String name) {
        BrainBalance brain = BrainBalance.fromConfig();
        if (name.startsWith("deck.slots.")) {
            return Integer.toString(DeckTier.valueOf(name.substring("deck.slots.".length()).toUpperCase(Locale.ROOT)).slots());
        }
        if (name.startsWith("brain.machines.")) {
            return BrainBalance.shown(brain.limit(true, Integer.parseInt(name.substring("brain.machines.".length()))));
        }
        return switch (name) {
            case "brain.withoutBrain" -> BrainBalance.shown(brain.limit(false, 0));
            case "brain.maxFloors" -> Integer.toString(brain.maxFloors());
            case "wild.spawnRate" -> JasmConfig.orDefault(JasmConfig.WILD_SPAWN_RATE) + "%";
            default -> "?";
        };
    }

    static final class RuleTag extends FlowTagCompiler {
        @Override
        public Set<String> getTagNames() {
            return Set.of("Rule");
        }

        @Override
        protected void compile(PageCompiler compiler, LytFlowParent parent, MdxJsxElementFields el) {
            try {
                parent.appendText(value(MdxAttrs.getString(el, "name", "")));
            } catch (IllegalArgumentException e) {
                parent.appendError(compiler, "Unknown rule " + MdxAttrs.getString(el, "name", ""), el);
            }
        }
    }

    static final class WhenTag extends BlockTagCompiler {
        @Override
        public Set<String> getTagNames() {
            return Set.of("WhenOn", "WhenOff");
        }

        @Override
        protected void compile(PageCompiler compiler, LytBlockContainer parent, MdxJsxElementFields el) {
            String name = MdxAttrs.getString(el, "feature", "");
            Feature feature;
            try {
                feature = Feature.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                parent.appendError(compiler, "Unknown feature " + name, el);
                return;
            }
            if (feature.on() == el.name().equals("WhenOn")) {
                compiler.compileBlockContext(el.children(), parent);
            }
        }
    }
}
