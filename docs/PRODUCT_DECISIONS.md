# Product decision: stock messaging

Product cards show the shopper's exact selected quantity with minus/plus controls. Availability uses “In stock” above five units, “Only a few left” at one to five, and “Currently sold out” at zero. We removed the exact warehouse count because routine inventory numbers add little value to this small consumer catalog. This is a product judgment, not a claim that exact counts are always wrong.

Five is an initial, centralized threshold in `frontend/src/App.tsx`, not a research-proven optimum. The message reflects the API's available-to-reserve stock at the last catalog fetch. Adding to the browser-local cart does not reserve stock or manufacture scarcity. Checkout revalidates inventory atomically; the display is not an availability guarantee. A production version should refresh availability on return to the page and recover gracefully from changed stock.

Research: [NN/g on visibility of system status](https://www.nngroup.com/articles/visibility-system-status/) supports clear feedback; [Baymard on identifying items already in the cart](https://baymard.com/research-articles/highlight-products-if-in-users-cart) supports explicit cart state. [IESE's research summary](https://www.iese.edu/insight/articles/online-retail-scarcity-signals/) describes context-dependent effects of scarcity disclosure, including potential returns; its flash-sale setting does not establish an optimal threshold for this project. Validate the threshold using shopper comprehension, checkout stock conflicts, conversion, and returns rather than assuming urgency is always beneficial.

For future decisions, evaluate the shopper's goal, clarity, trust, accessibility, failure recovery, and technical feasibility together. Record sources, distinguish evidence from assumptions, and recommend a default before asking for a preference.

