package dev.micolash.jasm.core;

import dev.micolash.jasm.config.JasmConfig;

/** The numbers {@link ChipOdds} and {@link Training} use. In game they come from the server config. */
public record ChipBalance(double ownTypeBitling, double advancedBasic, double advancedBitling, double advancedNibbling,
        int trainingBitling, int trainingNibbling) {
    public static ChipBalance fromConfig() {
        return new ChipBalance(JasmConfig.ODDS_OWN_TYPE_BITLING.get(), JasmConfig.ADVANCED_CHANCE_BASIC.get(),
                JasmConfig.ADVANCED_CHANCE_BITLING.get(), JasmConfig.ADVANCED_CHANCE_NIBBLING.get(),
                JasmConfig.TRAINING_BITLING.getAsInt(), JasmConfig.TRAINING_NIBBLING.getAsInt());
    }
}
