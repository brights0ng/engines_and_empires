// Written by ChatGPT.
package dev.brights0ng.empire.rules;

/**
 * Mutable civilian food state, following vanilla FoodData with a nonlethal starvation floor and daily intake.
 * Callers add basal exhaustion, count each active tick, and apply the returned healing or damage to the body.
 * Neither hunger nor intake counting depends on whether an empire member is online.
 */
public final class CivilianFood {

    /** PROVISIONAL (Bright): starvation stops at half of maximum health on every difficulty. */
    public static final float STARVE_FLOOR_FRACTION = 0.5F;
    /** PROVISIONAL (Bright): a day of this civilian's own ticking time. */
    public static final long DAY_LENGTH = 24_000L;

    private int foodLevel = 20;
    private float saturation = 5.0F;
    private float exhaustion;
    private int tickTimer;
    private int foodEatenToday;
    private int foodEatenYesterday;
    private long activeTicks;

    public void addExhaustion(float amount) {
        exhaustion = Math.min(exhaustion + amount, 40.0F);
    }

    /** Saturation is the absolute amount from FoodProperties.saturation(), not a modifier. */
    public void eat(int nutrition, float saturationAmount) {
        foodLevel = Math.clamp(foodLevel + nutrition, 0, 20);
        saturation = Math.clamp(saturation + saturationAmount, 0.0F, (float) foodLevel);
        foodEatenToday += nutrition;
    }

    /** Processes one tick, preserving vanilla's branch order and shared regeneration/starvation timer. */
    public TickResult tick(float health, float maxHealth, boolean naturalRegeneration, boolean peaceful) {
        if (exhaustion > 4.0F) {
            exhaustion -= 4.0F;
            if (saturation > 0.0F) {
                saturation = Math.max(saturation - 1.0F, 0.0F);
            } else if (!peaceful) {
                foodLevel = Math.max(foodLevel - 1, 0);
            }
        }

        // Player.isHurt also excludes dead bodies.
        boolean isHurt = health > 0.0F && health < maxHealth;
        if (naturalRegeneration && saturation > 0.0F && isHurt && foodLevel >= 20) {
            tickTimer++;
            if (tickTimer >= 10) {
                float amount = Math.min(saturation, 6.0F);
                addExhaustion(amount);
                tickTimer = 0;
                return new TickResult(amount / 6.0F, 0.0F);
            }
        } else if (naturalRegeneration && foodLevel >= 18 && isHurt) {
            tickTimer++;
            if (tickTimer >= 80) {
                addExhaustion(6.0F);
                tickTimer = 0;
                return new TickResult(1.0F, 0.0F);
            }
        } else if (foodLevel <= 0) {
            tickTimer++;
            if (tickTimer >= 80) {
                float floor = maxHealth * STARVE_FLOOR_FRACTION;
                tickTimer = 0;
                if (health > floor) {
                    return new TickResult(0.0F, Math.min(1.0F, health - floor));
                }
            }
        } else {
            tickTimer = 0;
        }
        return TickResult.NONE;
    }

    /** Unloaded time does not count; the day boundary follows the civilian rather than world time. */
    public void countActiveTick() {
        activeTicks++;
        if (activeTicks % DAY_LENGTH == 0) {
            foodEatenYesterday = foodEatenToday;
            foodEatenToday = 0;
        }
    }

    public int foodLevel() {
        return foodLevel;
    }

    public float saturation() {
        return saturation;
    }

    public float exhaustion() {
        return exhaustion;
    }

    public int tickTimer() {
        return tickTimer;
    }

    public int foodEatenToday() {
        return foodEatenToday;
    }

    public int foodEatenYesterday() {
        return foodEatenYesterday;
    }

    public long activeTicks() {
        return activeTicks;
    }

    public Saved save() {
        return new Saved(foodLevel, saturation, exhaustion, tickTimer, foodEatenToday, foodEatenYesterday, activeTicks);
    }

    /** Restores a saved snapshot verbatim, as FoodData does; persistence callers handle missing or corrupt fields. */
    public static CivilianFood restore(Saved saved) {
        CivilianFood food = new CivilianFood();
        food.foodLevel = saved.foodLevel();
        food.saturation = saved.saturation();
        food.exhaustion = saved.exhaustion();
        food.tickTimer = saved.tickTimer();
        food.foodEatenToday = saved.foodEatenToday();
        food.foodEatenYesterday = saved.foodEatenYesterday();
        food.activeTicks = saved.activeTicks();
        return food;
    }

    public CivilianFood copy() {
        return restore(save());
    }

    public record TickResult(float healAmount, float starveDamage) {
        private static final TickResult NONE = new TickResult(0.0F, 0.0F);
    }

    /** Plain values for persistence without any game or NBT dependency. */
    public record Saved(int foodLevel, float saturation, float exhaustion, int tickTimer,
                        int foodEatenToday, int foodEatenYesterday, long activeTicks) {
    }
}
