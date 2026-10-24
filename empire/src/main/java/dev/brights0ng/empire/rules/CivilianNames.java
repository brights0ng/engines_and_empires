// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.List;
import java.util.random.RandomGenerator;

/** The names a civilian may be given at spawn (Bright's list). */
public final class CivilianNames {

    public static final List<String> NAMES = List.of(
            "Aodhan", "Bran", "Cathal", "Ciaran", "Diarmuid", "Donnchadh", "Eoghan", "Fearghus", "Fionn", "Lorcan",
            "Niall", "Oisin", "Padraig", "Ruairi", "Seamus", "Tadhg", "Calum", "Lachlan", "Torcuil", "Ruadhan",
            "Ailbhe", "Aoife", "Brid", "Caoimhe", "Clodagh", "Deirdre", "Eilidh", "Eithne", "Fionnuala", "Grainne",
            "Iseabail", "Mairead", "Muireann", "Niamh", "Orlaith", "Roisin", "Saoirse", "Siobhan", "Sile", "Una");

    public static String pick(RandomGenerator random) {
        return NAMES.get(random.nextInt(NAMES.size()));
    }

    private CivilianNames() {
    }
}
