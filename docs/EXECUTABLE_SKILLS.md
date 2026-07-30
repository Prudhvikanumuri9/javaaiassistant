# Executable assistant skills

The cooking workflow is the first executable assistant skill. Unlike prompt-only
skills, it uses application-controlled functions against the local SQLite data.

## Cooking request flow

1. The selected reasoning model performs a constrained planning turn and chooses
   from `inventory.list`, `recipes.findByAvailableIngredients`, and
   `recipes.get`.
2. Java rejects unknown tool names and executes only the validated read-only
   calls selected by the model.
3. Recipes are ranked by the percentage of required ingredients currently in
   inventory and by number of missing ingredients.
4. The verified results, cooking time, missing ingredients, and recipe steps are
   supplied to the selected local reasoning model.
5. Chat displays a proposed **Plan … and add missing items** action for the
   highest-ranked recipe.
6. Nothing changes until the user selects **Confirm action**.
7. Confirmation creates today's dinner entry. The existing shopping-list
   calculation automatically displays required ingredients not in inventory.

The initial local recipe catalog includes Vegetable Fried Rice, Tomato Pasta,
Chickpea Curry, Vegetable Omelet, and Bean Tacos. Recipes are stored in
`data/home.db`, allowing a later recipe editor/import feature without rebuilding
the application.

Three transcript-derived test recipes are also loaded: Chili Paneer, Chilli
Chicken, and Crispy Corn. Their source VTT paths and extraction warnings are
stored in `recipe_sources`. The supplied captions contain obvious machine
transcription errors, so partially unclear measurements are estimates that
should be reviewed against the videos before relying on exact quantities.
For these samples, ingredients, required cookware/equipment, and ordered cooking
steps are stored separately. Recipe tool results include all three, while only
consumable ingredient shortages affect the shopping list.

## Permission model

- Inventory and recipe reads may execute automatically.
- Meal-plan and shopping-impact changes require explicit confirmation.
- Proposed action identifiers are single-use and held only in application
  memory. Restarting the application invalidates unconfirmed actions.
- The model never receives direct database access and cannot bypass Java-side
  validation.
- Transcript-derived recipes remain unverified drafts and are blocked from
  recipe, meal-plan, and shopping tools. Chat can offer **Ask model from general
  knowledge**; that follow-up disables all local tools and household context and
  cannot create confirmation actions or modify local data.

Example:

```text
What can I cook tonight?
```

The answer contains inventory-aware options. Confirming the action plans the
recommended dinner and makes its shortages appear on the weekly shopping list.
