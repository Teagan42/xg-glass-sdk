"""Config flow for xg.glass Smart Glasses."""
from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.helpers import selector

from .const import CONF_DEVICE_NAME, DOMAIN

_STEP_USER_SCHEMA = vol.Schema(
    {
        vol.Required(CONF_DEVICE_NAME, default="Rokid Glasses"): selector.TextSelector(
            selector.TextSelectorConfig(type=selector.TextSelectorType.TEXT)
        ),
    }
)


class XgGlassConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for xg.glass."""

    VERSION = 1

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Handle the initial step — ask for the device name."""
        if user_input is not None:
            device_name: str = user_input[CONF_DEVICE_NAME]
            unique_id = device_name.lower().replace(" ", "_")

            await self.async_set_unique_id(unique_id)
            self._abort_if_unique_id_configured()

            return self.async_create_entry(title=device_name, data=user_input)

        return self.async_show_form(
            step_id="user",
            data_schema=_STEP_USER_SCHEMA,
        )
