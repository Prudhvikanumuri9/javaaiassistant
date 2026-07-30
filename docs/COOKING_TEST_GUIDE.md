# Cooking tool test guide

Run these tests one at a time. Keep the application console visible so you can
verify the `Assistant model selected tools: [...]` line.

## Prepare partial inventory

Stop the application, then run:

```cmd
cd /d D:\workspace\AIAPPS\target\PersonalAssistant
load-cooking-test-data.cmd
start-assistant.cmd
```

The loader preserves existing records and is safe to run repeatedly. It adds
12 sample ingredients, including paneer, flour, corn flour, garlic, bell
peppers, soy sauce, rice, and eggs.

It intentionally does **not** add chili sauce, vinegar, spring onions, paprika,
baking powder, chicken, frozen sweet corn, white pepper, beaten egg, or
coriander. This gives the recipe and shopping tools known shortages to find.

## Test 1: Inventory read

Ask:

```text
Do I have paneer, bell peppers, and chili sauce?
```

Expected:

- Console includes `inventory.list`.
- The answer reports paneer and bell peppers from inventory.
- Chili sauce is reported as not currently recorded.
- No confirmation action appears because this is read-only.

## Test 2: Named recipe details

Ask:

```text
What ingredients, equipment, and steps do I need for Chili Paneer?
```

Expected:

- Console includes `recipes.get` with `Chili Paneer`.
- The answer lists ingredients, cookware, ordered steps, and shortages.
- A meal-plan confirmation proposal appears.

Do not confirm yet.

A details-only question must **not** display a confirmation proposal. A proposal
is allowed only when the request explicitly says to plan, schedule, add a meal,
or update the shopping list.

## Test 3: Inventory-ranked suggestions

Start a new chat and ask:

```text
What can I cook using what I have? Give me the best three options.
```

Expected:

- Console includes `inventory.list` and/or
  `recipes.findByAvailableIngredients`.
- Options are ranked using actual SQLite quantities.
- Missing ingredients are distinguished from available ingredients.

## Test 4: Safe write confirmation

Ask:

```text
Plan Chili Paneer for dinner and put anything missing on my shopping list.
```

Expected before confirmation:

- Nothing has changed in **Weekly meals**.
- A **Confirm action** button appears.

Select **Confirm action**, then verify:

- Chili Paneer appears in today's meal plan.
- Missing recipe ingredients appear under **Shopping list**.
- Available ingredients are subtracted when names and units match.

## Test 5: Single-use protection

Select the same confirmation button again, if still available.

Expected: the action is rejected as expired or already used and no duplicate is
created.

## Test 6: Manual shopping controls

Open **Shopping list** and add `Chili sauce`, quantity `2`, unit `bottles`.
Verify the quantity is displayed, remove that row, then add two sample rows and
select **Remove all**. The list should become empty after confirmation.

Each row also has **Edit**. After purchasing an item, check its box to reveal
**Add to inventory**. Shopping rows do not track quantities. The inventory
quantity and unit are requested only when moving the purchased item into
inventory. That action increases a compatible existing inventory quantity or
creates a new inventory record, then removes the shopping row.

## Gaps this test may reveal

- Small local models may select the wrong tool or omit a needed tool.
- Ingredient aliases such as `cornstarch` versus `corn flour` are not yet
  exhaustive. Common aliases for eggs, corn starch, bell peppers, spring
  onions, soy sauce, and sweet corn are normalized.
- Eggs support the cooking conversion `1 egg ≈ 3 tbsp beaten egg`. General unit
  conversion (`grams` versus `cups`, `each` versus `pieces`) is not yet
  implemented because it depends on ingredient density and size.
- The highest-ranked recipe is currently the only recipe offered as an
  executable confirmation action.
- Transcript-derived quantities still need human review against the videos.
